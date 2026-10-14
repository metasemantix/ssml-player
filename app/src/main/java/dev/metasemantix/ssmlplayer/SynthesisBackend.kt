package dev.metasemantix.ssmlplayer

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dev.metasemantix.ssmlplayer.core.Segment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface SynthesisBackend {
    suspend fun synthesize(segment: Segment.Speech, destination: File)
    fun stop()
    fun close()
}

/** All engine operations are made on the main dispatcher; callbacks may arrive on binder threads. */
class AndroidTtsBackend private constructor() : SynthesisBackend {
    private lateinit var tts: TextToSpeech
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    val engines: List<TextToSpeech.EngineInfo> get() = tts.engines.orEmpty()
    val engineName: String get() = tts.defaultEngine.orEmpty()
    val offlineVoices: List<Voice> get() = tts.voices.orEmpty().filter {
        !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
    }.sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))

    fun selectVoice(name: String) {
        val voice = offlineVoices.find { it.name == name }
            ?: error("Selected offline voice is unavailable. Install offline voice data in Android TTS settings.")
        check(tts.setVoice(voice) == TextToSpeech.SUCCESS) { "Engine rejected the selected voice." }
    }

    override suspend fun synthesize(segment: Segment.Speech, destination: File) {
        check(tts.setSpeechRate(segment.rate) == TextToSpeech.SUCCESS) { "Engine rejected speech rate." }
        check(tts.setPitch(segment.pitch) == TextToSpeech.SUCCESS) { "Engine rejected pitch." }
        val id = UUID.randomUUID().toString()
        val completion = CompletableDeferred<Unit>()
        pending[id] = completion
        try {
            check(tts.synthesizeToFile(segment.text, Bundle(), destination, id) == TextToSpeech.SUCCESS) {
                "Engine could not start synthesis. Check installed offline voice data."
            }
            check(withTimeoutOrNull(60000) { completion.await(); true } == true) { "TTS synthesis timed out after 60 seconds." }
        } finally {
            pending.remove(id)
        }
    }
    override fun stop() {
        tts.stop()
        pending.values.forEach { it.cancel() }
        pending.clear()
    }
    override fun close() { stop(); tts.shutdown() }

    companion object {
        suspend fun open(context: Context, engine: String? = null): AndroidTtsBackend {
            val backend = AndroidTtsBackend()
            val ready = CompletableDeferred<Int>()
            backend.tts = TextToSpeech(context.applicationContext, { ready.complete(it) }, engine)
            try {
                check(withTimeoutOrNull(15000) { ready.await() } == TextToSpeech.SUCCESS) {
                    "TextToSpeech initialization failed. Install an offline TTS engine and voice data."
                }
                backend.tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) { utteranceId?.let { backend.pending[it]?.complete(Unit) } }
                    @Deprecated("Legacy engine callback")
                    override fun onError(utteranceId: String?) { fail(utteranceId, "unknown") }
                    override fun onError(utteranceId: String?, errorCode: Int) { fail(utteranceId, errorCode.toString()) }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let { backend.pending[it]?.cancel() } }
                    private fun fail(id: String?, code: String) {
                        id?.let { backend.pending[it]?.completeExceptionally(IllegalStateException("TTS synthesis failed (engine error $code).")) }
                    }
                })
                return backend
            } catch (error: Throwable) {
                backend.close()
                throw error
            }
        }
    }
}
