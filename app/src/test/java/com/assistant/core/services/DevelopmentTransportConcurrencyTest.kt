package com.assistant.core.services

import com.jarvis.integration.DevelopmentBridgeClient
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DevelopmentTransportConcurrencyTest {
    @Test fun completedCallerCanSubmitItsNextPhaseWhileAnotherStatusReadWaits() {
        val pool = DevelopmentBridgeClient.newExecutor()
        val completing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = object : FutureTask<Int>(Callable { 1 }) {
            override fun done() { completing.countDown(); release.await(5, TimeUnit.SECONDS) }
        }
        try {
            pool.execute(first)
            assertTrue(completing.await(3, TimeUnit.SECONDS))
            assertEquals(1, first.get(1, TimeUnit.SECONDS).toInt())
            // Future completion precedes the worker's return to its queue.
            val status = pool.submit<Int> { 2 }
            val followUp = pool.submit<Int> { 3 }
            assertThrows(RejectedExecutionException::class.java) { pool.submit<Int> { 4 } }
            release.countDown()
            assertEquals(2, status.get(3, TimeUnit.SECONDS).toInt())
            assertEquals(3, followUp.get(3, TimeUnit.SECONDS).toInt())
            assertEquals(1, pool.largestPoolSize)
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(2, TimeUnit.SECONDS) }
    }
    @Test fun cancelledQueuedCallCannotExecuteWhenTheWorkerBecomesAvailable() {
        val pool = DevelopmentBridgeClient.newExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executed = AtomicBoolean(false)
        try {
            val first = pool.submit { started.countDown(); release.await(5, TimeUnit.SECONDS) }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            val cancelled = pool.submit { executed.set(true) }
            assertTrue(cancelled.cancel(true)); pool.purge()
            val next = pool.submit<Int> { 7 }
            release.countDown(); first.get(3, TimeUnit.SECONDS)
            assertEquals(7, next.get(3, TimeUnit.SECONDS).toInt())
            assertFalse(executed.get())
        } finally { release.countDown(); pool.shutdownNow(); pool.awaitTermination(2, TimeUnit.SECONDS) }
    }
}
