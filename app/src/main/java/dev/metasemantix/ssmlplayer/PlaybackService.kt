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
import dev.metasemantix.ssmlplayer.core.PcmAssembler
import dev.metasemantix.ssmlplayer.core.SerializedOperations
import dev.metasemantix.ssmlplayer.core.SpeechSession
import dev.metasemantix.ssmlplayer.core.Segment
import dev.metasemantix.ssmlplayer.core.SsmlParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    companion object {
        const val LOCAL_BIND = "dev.metasemantix.ssmlplayer.LOCAL_BIND"
        private val activeCaches = ConcurrentHashMap.newKeySet<String>()
    }
    inner class LocalBinder : Binder() { val service: PlaybackService get() = this@PlaybackService }
    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(PlayerState())
    val state = mutableState.asStateFlow()
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var audioDir: File
    private var backend: SpeechSession? = null
    private val operations = SerializedOperations(scope)
    private var cacheScavenged = false

    override fun onCreate() {
        super.onCreate()
        // A destroyed service's delayed worker cleanup must not remove a new instance's files.
        audioDir = File(cacheDir, "rendered-audio/${UUID.randomUUID()}")
        activeCaches.add(audioDir.absolutePath)
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
        invalidateAudio()
        update { copy(busy = true, voices = emptyList(), voice = "", status = "Loading offline voices…") }
        operations.replace {
            try {
                clearAudio()
                if (!cacheScavenged) {
                    withContext(Dispatchers.IO) {
                        // After process death the registry is empty; don't scavenge another live
                        // service instance still waiting for its cancelled Binder call to return.
                        audioDir.parentFile?.listFiles()?.filter { it.absolutePath !in activeCaches }
                            ?.forEach { it.deleteRecursively() }
                    }
                    cacheScavenged = true
                }
                backend?.close()
                backend = null
                val opened = SpeechSession.open { events -> AndroidTtsEngine(this@PlaybackService, engine, events) }
                backend = opened
                val catalog = opened.catalog
                update { copy(
                    engines = catalog.engines.map { Choice(it.id, it.label) },
                    engine = engine ?: catalog.defaultEngine,
                    voices = catalog.voices.map { Choice(it.id, it.label) },
                    voice = catalog.voices.firstOrNull()?.id.orEmpty(),
                    busy = false,
                    status = if (catalog.voices.isEmpty()) "No offline voices. Install voice data in Android TTS settings, then reload engines." else "Ready. Select an offline voice, then render SSML."
                ) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { update { copy(busy = false, status = error.message ?: "TTS initialization failed.") } }
        }
    }

    fun chooseVoice(name: String) {
        if (state.value.busy || state.value.voices.none { it.id == name }) return
        invalidateAudio()
        operations.replace { clearAudio() }
        update { copy(voice = name, status = "Voice selected. Render the document to listen.") }
    }

    fun documentChanged() {
        if (state.value.busy) return
        invalidateAudio()
        operations.replace { clearAudio() }
        update { copy(status = "Document changed. Render it to listen.") }
    }

    fun render(xml: String) {
        if (state.value.busy) return
        val engine = backend ?: run { update { copy(status = "No speech engine is ready. Reload engines.") }; return }
        val voice = state.value.voice
        if (voice.isEmpty()) { update { copy(status = "Select an installed offline voice first.") }; return }
        invalidateAudio()
        update { copy(busy = true, status = "Rendering locally… Keep the app open until ready.") }
        operations.replace {
            val directory = File(audioDir, UUID.randomUUID().toString())
            var published = false
            try {
                val document = withContext(Dispatchers.IO) { SsmlParser().parse(xml) }
                clearAudio()
                engine.selectVoice(voice)
                val lesson = withContext(Dispatchers.IO) {
                    check(directory.mkdirs()) { "Cannot create local audio cache." }
                    val output = File(directory, "lesson.wav")
                    val renderJob = coroutineContext.job
                    PcmAssembler(output).use { assembly ->
                        for ((index, segment) in document.segments.withIndex()) {
                            ensureActive()
                            withContext(Dispatchers.Main) {
                                update { copy(status = "Rendering segment ${index + 1}/${document.segments.size}… Keep the app open.") }
                            }
                            when (segment) {
                                is Segment.Speech -> {
                                    val speech = File(directory, "speech.wav")
                                    engine.synthesize(segment, speech)
                                    assembly.appendSpeech(speech) { renderJob.ensureActive() }
                                    check(speech.delete()) { "Cannot clean up temporary speech audio." }
                                }
                                is Segment.Silence -> assembly.appendSilence(segment.milliseconds) { renderJob.ensureActive() }
                            }
                        }
                        ensureActive()
                        assembly.finish()
                    }
                    output
                }
                ensureActive()
                player.setMediaItem(MediaItem.Builder().setUri(android.net.Uri.fromFile(lesson)).setMediaId("lesson")
                    .setMediaMetadata(MediaMetadata.Builder().setTitle("SSML lesson").setArtist("Offline speech").build()).build())
                player.prepare()
                published = true
                update { copy(busy = false, ready = true, status = "Rendered ${document.segments.size} segments into one local lesson. Press Play.") }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                invalidateAudio()
                update { copy(
                    busy = false,
                    voice = if (engine.isClosed) "" else voice,
                    voices = if (engine.isClosed) emptyList() else voices,
                    status = "Render failed: ${error.message ?: "unknown engine error"}" + if (engine.isClosed) " Reload engines." else "",
                ) }
            } finally {
                if (!published) withContext(NonCancellable + Dispatchers.IO) { directory.deleteRecursively() }
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
        player.pause()
        if (state.value.ready) player.seekToDefaultPosition(0)
        update { copy(busy = true, playing = false, status = "Stopping… Waiting for engine cleanup.") }
        operations.replace {
            if (!state.value.ready) clearAudio()
            val unavailable = backend?.isClosed == true
            update { copy(
                busy = false,
                voice = if (unavailable) "" else voice,
                voices = if (unavailable) emptyList() else voices,
                status = if (unavailable) "Speech engine unavailable after cleanup. Reload engines." else "Stopped.",
            ) }
        }
    }
    private fun invalidateAudio() {
        player.stop()
        player.clearMediaItems()
        update { copy(ready = false, playing = false) }
    }
    private suspend fun clearAudio() {
        // Player calls stay on Main; file operations wait for prior engine/assembly cleanup on IO.
        invalidateAudio()
        withContext(Dispatchers.IO) {
            audioDir.listFiles()?.forEach { it.deleteRecursively() }
        }
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady) { stop(); stopSelf() }
    }
    override fun onDestroy() {
        val previous = operations.cancel()
        scope.cancel()
        // Lifecycle callbacks must return promptly even when an engine's Binder call is slow.
        // Join first; then release the engine and only this service instance's cache directory.
        CoroutineScope(Dispatchers.IO).launch {
            try { previous?.join(); backend?.close() }
            catch (error: Exception) { android.util.Log.w("SSML Player", "Speech engine cleanup failed.", error) }
            finally {
                try { audioDir.deleteRecursively() } finally { activeCaches.remove(audioDir.absolutePath) }
            }
        }
        session.release()
        player.release()
        super.onDestroy()
    }
}
