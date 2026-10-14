package dev.metasemantix.ssmlplayer.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Implementations may block on Binder or disk. Only SpeechSession invokes these methods. */
interface SpeechEngine {
    fun catalog(): SpeechCatalog
    fun selectVoice(name: String)
    fun synthesize(segment: Segment.Speech, destination: File, utteranceId: String)
    fun stop()
    fun shutdown()
}
data class SpeechChoice(val id: String, val label: String)
data class SpeechCatalog(val engines: List<SpeechChoice>, val voices: List<SpeechChoice>, val defaultEngine: String)
interface SpeechEvents {
    fun initialized(success: Boolean)
    fun completed(id: String?)
    fun failed(id: String?, message: String)
}

/**
 * Keeps construction, Binder calls, file opening and cleanup off the caller/UI dispatcher.
 * Cancellation waits for in-flight blocking calls and stop before returning. A hung Binder
 * transaction cannot be forcibly interrupted; timeouts are not hard Binder deadlines.
 */
class SpeechSession private constructor(private val worker: CoroutineDispatcher) {
    private var engine: SpeechEngine? = null
    private val closed = AtomicBoolean()
    val isClosed: Boolean get() = closed.get()
    private val initialized = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    lateinit var catalog: SpeechCatalog
        private set
    private val events = object : SpeechEvents {
        override fun initialized(success: Boolean) { initialized.complete(success) }
        override fun completed(id: String?) { id?.let { pending[it]?.complete(Unit) } }
        override fun failed(id: String?, message: String) {
            id?.let { pending[it]?.completeExceptionally(IllegalStateException(message)) }
        }
    }
    suspend fun selectVoice(name: String) = withContext(worker) {
        ensureActive()
        check(!closed.get()) { "Speech engine is closed." }
        requireNotNull(engine).selectVoice(name)
    }
    suspend fun synthesize(segment: Segment.Speech, destination: File, timeoutMs: Long = 60000) {
        val id = UUID.randomUUID().toString()
        val completion = CompletableDeferred<Unit>()
        pending[id] = completion
        try {
            check(withTimeoutOrNull(timeoutMs) {
                withContext(worker) {
                    ensureActive()
                    check(!closed.get()) { "Speech engine is closed." }
                    requireNotNull(engine).synthesize(segment, destination, id)
                }
                completion.await()
                true
            } == true) { "TTS synthesis timed out. The engine may still be finishing a blocking call." }
        } catch (error: Throwable) {
            // Do not return to the caller (which may remove/reuse files) until stop returns.
            try { stop() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        } finally {
            pending.remove(id)
        }
    }
    suspend fun stop() = withContext(NonCancellable + worker) {
        try { engine?.stop() }
        catch (error: Throwable) {
            // A failed stop leaves engine state unknown. Retire it instead of submitting new work.
            if (closed.compareAndSet(false, true)) {
                try { engine?.shutdown() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            }
            throw error
        } finally {
            pending.values.forEach { it.cancel() }
            pending.clear()
        }
    }
    suspend fun close() = withContext(NonCancellable + worker) {
        if (closed.compareAndSet(false, true)) {
            try { stop() } finally { engine?.shutdown() }
        }
    }
    companion object {
        suspend fun open(
            worker: CoroutineDispatcher = Dispatchers.IO,
            timeoutMs: Long = 15000,
            factory: (SpeechEvents) -> SpeechEngine,
        ): SpeechSession {
            val session = SpeechSession(worker)
            try {
                check(withTimeoutOrNull(timeoutMs) {
                    withContext(worker) {
                        // Assign inside the worker: prompt cancellation on return must not leak it.
                        session.engine = factory(session.events)
                    }
                    check(session.initialized.await()) {
                        "TextToSpeech initialization failed. Install an offline engine and voice data."
                    }
                    session.catalog = withContext(worker) { requireNotNull(session.engine).catalog() }
                    true
                } == true) { "TTS initialization timed out. The engine may still be finishing a blocking call." }
                return session
            } catch (error: Throwable) {
                try { session.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                throw error
            }
        }
    }
}
