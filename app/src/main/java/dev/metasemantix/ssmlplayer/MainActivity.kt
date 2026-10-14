package dev.metasemantix.ssmlplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.metasemantix.ssmlplayer.core.SsmlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.CodingErrorAction

class MainActivity : ComponentActivity() {
    private var service by mutableStateOf<PlaybackService?>(null)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { service = (binder as PlaybackService.LocalBinder).service }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindService(Intent(this, PlaybackService::class.java).setAction(PlaybackService.LOCAL_BIND), connection, Context.BIND_AUTO_CREATE)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val bound = service
                    if (bound == null) {
                        Column(Modifier.safeDrawingPadding().padding(24.dp)) { Text("Connecting to offline player…") }
                    } else PlayerScreen(bound)
                }
            }
        }
    }
    override fun onDestroy() { unbindService(connection); super.onDestroy() }

    @Composable
    private fun PlayerScreen(player: PlaybackService) {
        val state by player.state.collectAsStateWithLifecycle()
        // Bound input keeps saved instance state below the Binder limit, including UTF-16 overhead.
        var xml by rememberSaveable { mutableStateOf(SAMPLE) }
        var importError by remember { mutableStateOf<String?>(null) }
        var importing by remember { mutableStateOf(false) }
        val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                importing = true
                lifecycleScope.launch {
                    try {
                        val imported = withContext(Dispatchers.IO) {
                            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                            val stream = contentResolver.openInputStream(uri) ?: error("Document provider returned no data.")
                            stream.use { input ->
                                java.io.InputStreamReader(input, decoder).use { reader ->
                                    val text = StringBuilder()
                                    val buffer = CharArray(4096)
                                    while (true) {
                                        val count = reader.read(buffer)
                                        if (count < 0) break
                                        require(text.length + count <= SsmlParser.MAX_INPUT_CHARS) { "Document exceeds 128 Ki characters." }
                                        text.append(buffer, 0, count)
                                    }
                                    text.toString()
                                }
                            }
                        }
                        player.documentChanged()
                        xml = imported
                        importError = null
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        importError = "Import failed: ${error.message ?: "cannot read UTF-8 document"}"
                    } finally { importing = false }
                }
            }
        }
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("SSML Player", style = MaterialTheme.typography.headlineMedium)
            Text("Local documents • Offline voices • No accounts")
            Text("Render while this screen is open. Once ready, playback supports the lock screen and background controls.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { import.launch(arrayOf("*/*")) }, enabled = !state.busy && !importing) { Text(if (importing) "Importing…" else "Import SSML") }
                OutlinedButton(onClick = { player.documentChanged(); xml = SAMPLE; importError = null }, enabled = !state.busy && !importing) { Text("Sample") }
            }
            OutlinedTextField(
                value = xml,
                onValueChange = { if (it.length <= SsmlParser.MAX_INPUT_CHARS) { player.documentChanged(); xml = it; importError = null } else importError = "Document exceeds 128 Ki characters." },
                label = { Text("SSML — paste or edit here") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 320.dp),
                enabled = !state.busy && !importing,
            )
            ChoiceMenu("Engine", state.engines, state.engine, !state.busy, player::chooseEngine)
            ChoiceMenu("Offline voice", state.voices, state.voice, !state.busy, player::chooseVoice)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                    catch (_: android.content.ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                }) { Text("TTS settings") }
                OutlinedButton(onClick = { player.chooseEngine(state.engine.ifEmpty { null }) }, enabled = !state.busy) { Text("Reload engines") }
            }
            Button(onClick = { player.render(xml) }, enabled = !state.busy && !importing && state.voice.isNotEmpty()) { Text("Render locally") }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = player::playPause, enabled = state.ready && !state.busy) { Text(if (state.playing) "Pause" else "Play") }
                OutlinedButton(onClick = player::stop, enabled = state.ready || state.busy) { Text(if (state.busy) "Cancel" else "Stop") }
            }
            if (state.busy || importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            importError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(state.status, style = MaterialTheme.typography.bodyLarge)
            Text("Engine rate/pitch and pronunciation vary. Unsupported SSML causes an error. Explicit breaks are inserted into one PCM lesson. The engine may add silence within its speech output.")
        }
    }
}

@Composable
private fun ChoiceMenu(label: String, choices: List<Choice>, selected: String, enabled: Boolean, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled && choices.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${choices.find { it.id == selected }?.label ?: "None available"}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(text = { Text(choice.label) }, onClick = { expanded = false; select(choice.id) })
            }
        }
    }
}

private const val SAMPLE = """<speak version="1.0">
  <p>Welcome to your offline SSML lesson.</p>
  <break time="1500ms"/>
  <p><prosody rate="slow" pitch="low">Take your time.
    <prosody rate="fast" pitch="+20%">This part has nested prosody.</prosody>
  </prosody></p>
  <break time="1s"/>
  <s>You can pause, resume, or stop this lesson.</s>
</speak>"""
