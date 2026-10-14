package dev.metasemantix.ssmlplayer.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Caller-confined (the service uses Main). Replacement waits for cancelled work's cleanup. */
class SerializedOperations(private val scope: CoroutineScope) {
    private var tail: Job? = null
    fun replace(block: suspend CoroutineScope.() -> Unit): Job {
        val previous = cancel()
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Even a replacement cancelled while waiting must preserve the cleanup chain.
            withContext(NonCancellable) { previous?.join() }
            ensureActive()
            block()
        }.also { tail = it }
    }
    fun cancel(): Job? = tail.also { it?.cancel() }
}
