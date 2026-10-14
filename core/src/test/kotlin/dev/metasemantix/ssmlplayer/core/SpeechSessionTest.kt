package dev.metasemantix.ssmlplayer.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SpeechSessionTest {
    private val speech = Segment.Speech("Hello", 1f, 1f)
    private val destination = File("unused-by-fake.wav")
    private open class FakeEngine(val events: SpeechEvents) : SpeechEngine {
        val stopped = AtomicInteger()
        val shutdown = AtomicInteger()
        val requests = Channel<String>(Channel.UNLIMITED)
        override fun catalog() = SpeechCatalog(emptyList(), listOf(SpeechChoice("offline", "Offline")), "engine")
        override fun selectVoice(name: String) = Unit
        override fun synthesize(segment: Segment.Speech, destination: File, utteranceId: String) { requests.trySend(utteranceId) }
        override fun stop() { stopped.incrementAndGet() }
        override fun shutdown() { shutdown.incrementAndGet() }
    }
    @Test fun constructorCatalogVoiceSubmissionStopAndShutdownStayOffCallerThread() = runBlocking {
        val caller = Thread.currentThread()
        val worker = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        val calls = mutableListOf<String>()
        fun offCaller(name: String) { assertNotSame(caller, Thread.currentThread()); synchronized(calls) { calls += name } }
        try {
            val session = SpeechSession.open(worker) { events ->
                offCaller("construct")
                events.initialized(true) // Callback may arrive before the constructor returns.
                object : FakeEngine(events) {
                    override fun catalog(): SpeechCatalog { offCaller("catalog"); return super.catalog() }
                    override fun selectVoice(name: String) { offCaller("voice") }
                    override fun synthesize(segment: Segment.Speech, destination: File, utteranceId: String) {
                        offCaller("submit"); events.completed(utteranceId)
                    }
                    override fun stop() { offCaller("stop") }
                    override fun shutdown() { offCaller("shutdown") }
                }
            }
            session.selectVoice("offline")
            session.synthesize(speech, destination)
            session.close()
            session.close()
            assertEquals(listOf("construct", "catalog", "voice", "submit", "stop", "shutdown"), calls)
        } finally { worker.close() }
    }
    @Test fun cancelDuringBlockingConstructionKeepsCallerResponsiveAndClosesLateEngine() = runBlocking {
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        lateinit var fake: FakeEngine
        try {
            val opening = launch {
                SpeechSession.open(worker) { events ->
                    fake = FakeEngine(events)
                    entered.complete(Unit)
                    check(release.await(5, TimeUnit.SECONDS))
                    events.initialized(true)
                    fake
                }
                error("Cancelled initialization must not publish an engine")
            }
            withTimeout(5000) { entered.await() }
            opening.cancel()
            val heartbeat = async { "UI responsive" }
            assertEquals("UI responsive", heartbeat.await())
            assertFalse(opening.isCompleted) // Cleanup waits without blocking the caller.
            release.countDown()
            withTimeout(5000) { opening.join() }
            assertEquals(1, fake.stopped.get())
            assertEquals(1, fake.shutdown.get())
        } finally { release.countDown(); worker.close() }
    }
    @Test fun cancelledSubmissionWaitsForBlockingCallAndStopBeforeReturning() = runBlocking {
        val worker = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        val submitted = CompletableDeferred<Unit>()
        val stopping = CompletableDeferred<Unit>()
        val releaseSubmit = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        lateinit var fake: FakeEngine
        try {
            val session = SpeechSession.open(worker) { events ->
                events.initialized(true)
                object : FakeEngine(events) {
                    override fun synthesize(segment: Segment.Speech, destination: File, utteranceId: String) {
                        submitted.complete(Unit)
                        check(releaseSubmit.await(5, TimeUnit.SECONDS))
                    }
                    override fun stop() {
                        super.stop()
                        stopping.complete(Unit)
                        check(releaseStop.await(5, TimeUnit.SECONDS))
                    }
                }.also { fake = it }
            }
            val job = launch { session.synthesize(speech, destination) }
            withTimeout(5000) { submitted.await() }
            job.cancel()
            assertEquals(42, async { 42 }.await())
            assertFalse(job.isCompleted)
            releaseSubmit.countDown()
            withTimeout(5000) { stopping.await() }
            assertFalse(job.isCompleted)
            releaseStop.countDown()
            withTimeout(5000) { job.join() }
            assertEquals(1, fake.stopped.get())
            session.close()
        } finally { releaseSubmit.countDown(); releaseStop.countDown(); worker.close() }
    }
    @Test fun failedInitializationAndTimeoutBothReleaseEngine() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        for (ready in listOf(false, null)) {
            lateinit var fake: FakeEngine
            val error = runCatching {
                SpeechSession.open(worker, timeoutMs = 100) { events ->
                    FakeEngine(events).also { fake = it; ready?.let(events::initialized) }
                }
            }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertEquals(1, fake.stopped.get())
            assertEquals(1, fake.shutdown.get())
        }
    }
    @Test fun lateCallbacksDoNotFinishNextUtteranceAndInterruptionIsAnError() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        lateinit var fake: FakeEngine
        val session = SpeechSession.open(worker) { events ->
            events.initialized(true)
            FakeEngine(events).also { fake = it }
        }
        try {
            val first = launch { session.synthesize(speech, destination) }
            val firstId = fake.requests.receive()
            first.cancelAndJoin()
            val second = async { runCatching { session.synthesize(speech, destination) } }
            val secondId = fake.requests.receive()
            fake.events.completed(firstId)
            fake.events.failed(firstId, "late error")
            fake.events.completed(null)
            assertFalse(second.isCompleted)
            fake.events.failed(secondId, "engine interrupted")
            assertEquals("engine interrupted", second.await().exceptionOrNull()?.message)
        } finally { session.close() }
    }
    @Test fun cancellationWhileWaitingForInitClosesWithoutWaitingForCallback() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val constructed = CompletableDeferred<Unit>()
        lateinit var fake: FakeEngine
        var catalogCalls = 0
        val opening = launch {
            SpeechSession.open(worker) { events ->
                object : FakeEngine(events) {
                    override fun catalog(): SpeechCatalog { catalogCalls++; return super.catalog() }
                }.also { fake = it; constructed.complete(Unit) }
            }
            error("Cancelled engine must not be published")
        }
        constructed.await()
        opening.cancelAndJoin()
        fake.events.initialized(true)
        assertEquals(0, catalogCalls)
        assertEquals(1, fake.shutdown.get())
    }
    @Test fun cleanupFailureDoesNotReplaceInitializationError() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val error = runCatching {
            SpeechSession.open(worker) { events ->
                events.initialized(false)
                object : FakeEngine(events) {
                    override fun shutdown() { error("shutdown failed") }
                }
            }
        }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("initialization failed"))
        assertEquals("shutdown failed", error.suppressed.single().message)
    }
    @Test fun missingSynthesisCallbackTimesOutAndStopsBeforeRetry() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        lateinit var fake: FakeEngine
        val session = SpeechSession.open(worker) { events ->
            events.initialized(true)
            FakeEngine(events).also { fake = it }
        }
        try {
            val error = runCatching { session.synthesize(speech, destination, timeoutMs = 100) }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertEquals(1, fake.stopped.get())
            val retry = launch { session.synthesize(speech, destination) }
            fake.requests.receive() // Timed-out request
            val retryId = fake.requests.receive()
            fake.events.completed(retryId)
            retry.join()
        } finally { session.close() }
    }
}
