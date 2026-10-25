package com.jarvis.developer

import java.io.InputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class PatchPreviewCancellationTest {
    @Test fun cancellationIsPermanentIdempotentAndStopsLateResourcesWithoutRetainingReleasedOnes() {
        val cancellation = PatchPreviewCancellation { it() }
        var stopped = 0
        var released = 0
        cancellation.registerStop { released++ }.close()
        cancellation.registerStop { stopped++ }
        cancellation.cancel(); cancellation.cancel()
        cancellation.registerStop { stopped++ }.close()
        assertTrue(cancellation.isCancelled); assertEquals(2, stopped); assertEquals(0, released)
        try { cancellation.check(); fail("Cancelled token cannot be reused") } catch (_: IllegalStateException) { }
    }

    @Test fun cleanupFailuresCannotPreventOtherResourcesFromStoppingOrRestoreTheToken() {
        val cancellation = PatchPreviewCancellation { it() }
        var stopped = false
        cancellation.registerStop { throw IOException("simulated private transport content") }
        cancellation.registerStop { stopped = true }
        cancellation.cancel()
        assertTrue(stopped); assertTrue(cancellation.isCancelled)
    }

    @Test fun blockedCleanupRunsOffTheCallingThread() {
        val cancellation = PatchPreviewCancellation()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val caller = Thread.currentThread()
        var cleanupThread: Thread? = null
        cancellation.registerStop {
            cleanupThread = Thread.currentThread(); started.countDown()
            assertTrue(release.await(3, TimeUnit.SECONDS))
        }
        try {
            cancellation.cancel()
            assertTrue(cancellation.isCancelled)
            assertTrue(started.await(2, TimeUnit.SECONDS)); assertNotSame(caller, cleanupThread)
        } finally { release.countDown() }
    }

    @Test fun cancellingABlockedRepositoryStreamClosesItAndPreventsTheNextGet() {
        val cancellation = PatchPreviewCancellation()
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val gets = AtomicInteger()
        val responseClosed = AtomicInteger()
        val stream = object : InputStream() {
            override fun read(): Int = error("Buffered read expected")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                reading.countDown()
                if (!closed.await(3, TimeUnit.SECONDS)) error("Cancellation did not close stream")
                throw IOException("simulated private stream content")
            }
            override fun close() { closed.countDown() }
        }
        val reader = RepositoryIntelligence.Reader(RepositoryIntelligence.GetTransport { url, _ ->
            gets.incrementAndGet()
            RepositoryIntelligence.GetResponse(200, -1, url, stream) { responseClosed.incrementAndGet() }
        }, cancellation)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean> {
                try { reader.inventory(""); false } catch (_: Exception) { true }
            }
            assertTrue(reading.await(2, TimeUnit.SECONDS))
            cancellation.cancel()
            assertTrue(result.get(2, TimeUnit.SECONDS))
            assertEquals(1, gets.get()); assertEquals(1, responseClosed.get())
        } finally { closed.countDown(); executor.shutdownNow() }
    }
}
