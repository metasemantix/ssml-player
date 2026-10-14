package dev.metasemantix.ssmlplayer.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SerializedOperationsTest {
    @Test fun rapidReplacementWaitsForCancelledEnginesCleanupBeforeStartingNewWork() = runTest {
        val slot = SerializedOperations(this)
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        slot.replace {
            try { awaitCancellation() } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    allowCleanup.await()
                    events += "old files closed"
                }
            }
        }
        slot.replace { events += "should never start" }
        cleanupStarted.await()
        val latest = slot.replace { events += "new render" }
        runCurrent()
        assertTrue(events.isEmpty())
        assertTrue(latest.isActive) // Main/caller is free while cleanup is still suspended.
        allowCleanup.complete(Unit)
        latest.join()
        assertEquals(listOf("old files closed", "new render"), events)
    }
    @Test fun destructionCanWaitForTheEntireCancellationChain() = runTest {
        val slot = SerializedOperations(this)
        val cleanup = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()
        slot.replace {
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { allowCleanup.await(); cleanup.complete(Unit) }
            }
        }
        slot.replace { error("Cancelled replacement must not start") }
        val tail = slot.cancel()!!
        runCurrent()
        assertFalse(tail.isCompleted)
        allowCleanup.complete(Unit)
        tail.join()
        assertTrue(cleanup.isCompleted)
    }
}
