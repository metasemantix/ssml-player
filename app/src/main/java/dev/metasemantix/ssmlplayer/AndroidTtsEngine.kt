package dev.metasemantix.ssmlplayer

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dev.metasemantix.ssmlplayer.core.Segment
import dev.metasemantix.ssmlplayer.core.SpeechCatalog
import dev.metasemantix.ssmlplayer.core.SpeechChoice
import dev.metasemantix.ssmlplayer.core.SpeechEngine
import dev.metasemantix.ssmlplayer.core.SpeechEvents
import java.io.File

/** Blocking Android adapter. SpeechSession dispatches every call, including construction, to IO. */
class AndroidTtsEngine(context: Context, engine: String?, events: SpeechEvents) : SpeechEngine {
    private val tts = TextToSpeech(context.applicationContext, { events.initialized(it == TextToSpeech.SUCCESS) }, engine)
    private var voices: List<Voice> = emptyList()

    init {
        try {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { events.completed(utteranceId) }
                @Deprecated("Legacy engine callback")
                override fun onError(utteranceId: String?) { fail(utteranceId, "unknown") }
                override fun onError(utteranceId: String?, errorCode: Int) { fail(utteranceId, errorCode.toString()) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { fail(utteranceId, "interrupted") }
                private fun fail(id: String?, code: String) { events.failed(id, "TTS synthesis failed (engine error $code).") }
            })
        } catch (error: Throwable) {
            tts.shutdown()
            throw error
        }
    }
    override fun catalog(): SpeechCatalog {
        voices = tts.voices.orEmpty().filter {
            !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
        }.sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))
        return SpeechCatalog(
            tts.engines.orEmpty().map { SpeechChoice(it.name, it.label) },
            voices.map { SpeechChoice(it.name, "${it.locale.toLanguageTag()} — ${it.name}") },
            tts.defaultEngine.orEmpty(),
        )
    }
    override fun selectVoice(name: String) {
        val voice = voices.find { it.name == name }
            ?: error("Selected offline voice is unavailable. Install offline voice data in Android TTS settings.")
        check(tts.setVoice(voice) == TextToSpeech.SUCCESS) { "Engine rejected the selected voice." }
    }
    override fun synthesize(segment: Segment.Speech, destination: File, utteranceId: String) {
        check(tts.setSpeechRate(segment.rate) == TextToSpeech.SUCCESS) { "Engine rejected speech rate." }
        check(tts.setPitch(segment.pitch) == TextToSpeech.SUCCESS) { "Engine rejected pitch." }
        check(tts.synthesizeToFile(segment.text, Bundle(), destination, utteranceId) == TextToSpeech.SUCCESS) {
            "Engine could not start synthesis. Check installed offline voice data."
        }
    }
    override fun stop() { check(tts.stop() == TextToSpeech.SUCCESS) { "Speech engine could not stop. Reload engines." } }
    override fun shutdown() { tts.shutdown() }
}
