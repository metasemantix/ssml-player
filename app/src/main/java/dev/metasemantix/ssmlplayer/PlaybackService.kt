package dev.metasemantix.ssmlplayer

import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.metasemantix.ssmlplayer.core.PcmWav
import dev.metasemantix.ssmlplayer.core.Segment
import dev.metasemantix.ssmlplayer.core.SilenceWav
import dev.metasemantix.ssmlplayer.core.SsmlParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Only metadata and local paths enter Media3; SSML and speech never leave this process/device.
data class Choice(val id: String, val label: String)
data class PlayerState(
    val status: String = "Initializing offline speech…",
    val busy: Boolean = true,
    val engines: List<Choice> = emptyList(),
    val voices: List<Choice> = emptyList(),
    val engine: String = "",
    val voice: String = "",
    val ready: Boolean = false,
    val playing: Boolean = false,
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    companion object { const val LOCAL_BIND = "dev.metasemantix.ssmlplayer.LOCAL_BIND" }
    inner class LocalBinder : Binder() { val service: PlaybackService get() = this@PlaybackService }
    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(PlayerState())
    val state = mutableState.asStateFlow()
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var audioDir: File
    private var backend: AndroidTtsBackend? = null
    private var operation: Job? = null

    override fun onCreate() {
        super.onCreate()
        audioDir = File(cacheDir, "rendered-audio").apply { mkdirs() }
        audioDir.listFiles()?.forEach { it.deleteRecursively() }
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { update { copy(playing = isPlaying) } }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) update { copy(status = "Finished. Play to listen again.", playing = false) }
                }
                override fun onPlayerError(error: PlaybackException) {
                    update { copy(status = "Playback failed: ${error.errorCodeName}. Render again or try another offline voice.", playing = false, ready = false) }
                }
            })
        }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setSessionActivity(activity).build()
        chooseEngine(null)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session
    override fun onBind(intent: Intent?): IBinder? = if (intent?.action == LOCAL_BIND) binder else super.onBind(intent)

    private fun update(block: PlayerState.() -> PlayerState) { mutableState.value = mutableState.value.block() }

    fun chooseEngine(engine: String?) {
        cancelOperation()
        clearAudio()
        backend?.close()
        backend = null
        update { copy(busy = true, ready = false, playing = false, voices = emptyList(), voice = "", status = "Loading offline voices…") }
        operation = scope.launch {
            try {
                val opened = AndroidTtsBackend.open(this@PlaybackService, engine)
                backend = opened
                val voices = opened.offlineVoices
                update { copy(
                    engines = opened.engines.map { Choice(it.name, it.label) },
                    engine = engine ?: opened.engineName,
                    voices = voices.map { Choice(it.name, "${it.locale.toLanguageTag()} — ${it.name}") },
                    voice = voices.firstOrNull()?.name.orEmpty(),
                    busy = false,
                    status = if (voices.isEmpty()) "No offline voices. Install voice data in Android TTS settings, then reload engines." else "Ready. Select an offline voice, then render SSML."
                ) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { update { copy(busy = false, status = error.message ?: "TTS initialization failed.") } }
        }
    }

    fun chooseVoice(name: String) {
        if (state.value.busy) return
        if (state.value.voices.none { it.id == name }) return
        clearAudio()
        update { copy(voice = name, status = "Voice selected. Render the document to listen.") }
    }

    fun documentChanged() {
        if (state.value.busy) return
        clearAudio()
        update { copy(status = "Document changed. Render it to listen.") }
    }

    fun render(xml: String) {
        if (state.value.busy) return
        val document = try { SsmlParser().parse(xml) } catch (error: Exception) {
            update { copy(status = error.message ?: "Invalid SSML.") }; return
        }
        val engine = backend ?: run { update { copy(status = "No speech engine is ready. Reload engines.") }; return }
        if (state.value.voice.isEmpty()) { update { copy(status = "Select an installed offline voice first.") }; return }
        cancelOperation()
        clearAudio()
        update { copy(busy = true, status = "Rendering locally… Keep the app open until ready.") }
        operation = scope.launch {
            try {
                engine.selectVoice(state.value.voice)
                val files = mutableListOf<File>()
                var totalBytes = 0L
                for ((index, segment) in document.segments.withIndex()) {
                    ensureActive()
                    update { copy(status = "Rendering segment ${index + 1}/${document.segments.size}… Keep the app open.") }
                    if (segment is Segment.Silence && segment.milliseconds == 0L) continue
                    val file = File(audioDir, "$index.wav")
                    when (segment) {
                        is Segment.Speech -> {
                            engine.synthesize(segment, file)
                            withContext(Dispatchers.IO) { PcmWav.validate(file) }
                        }
                        is Segment.Silence -> withContext(Dispatchers.IO) {
                            file.outputStream().buffered().use { SilenceWav.write(it, segment.milliseconds) }
                        }
                    }
                    totalBytes += file.length()
                    check(totalBytes <= 128L * 1024 * 1024) { "Rendered audio exceeds 128 MiB. Split the document into smaller lessons." }
                    files += file
                }
                player.setMediaItems(files.mapIndexed { index, file ->
                    MediaItem.Builder().setUri(android.net.Uri.fromFile(file)).setMediaId(index.toString())
                        .setMediaMetadata(MediaMetadata.Builder().setTitle("SSML lesson").setArtist("Offline speech").build()).build()
                })
                player.prepare()
                update { copy(busy = false, ready = true, status = "Rendered ${files.size} segments locally. Press Play.") }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                engine.stop()
                clearAudio()
                update { copy(busy = false, status = "Render failed: ${error.message ?: "unknown engine error"}") }
            }
        }
    }

    fun playPause() {
        if (!state.value.ready || state.value.busy) return
        if (player.isPlaying) {
            player.pause()
            update { copy(status = "Paused.") }
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition(0)
            player.play()
            update { copy(status = "Playing offline audio.") }
        }
    }
    fun stop() {
        cancelOperation()
        player.pause()
        if (state.value.ready) player.seekToDefaultPosition(0) else clearAudio()
        update { copy(busy = false, playing = false, status = "Stopped.") }
    }
    private fun cancelOperation() { operation?.cancel(); operation = null; backend?.stop() }
    private fun clearAudio() {
        player.stop()
        player.clearMediaItems()
        audioDir.listFiles()?.forEach { it.delete() }
        update { copy(ready = false, playing = false) }
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady) { stop(); stopSelf() }
    }
    override fun onDestroy() {
        scope.cancel()
        backend?.close()
        session.release()
        player.release()
        audioDir.deleteRecursively()
        super.onDestroy()
    }
}
