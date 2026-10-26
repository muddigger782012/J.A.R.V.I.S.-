package com.jarvis.developer

import android.os.Debug
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Exercises Android's real regex implementation, not the desktop JVM's different engine. */
class RepositoryAnalysisRuntimeTest {
    @Test(timeout = 60_000) fun denseSourceKeepsNativeMemoryBoundedAndResolvesDependencies() {
        val controller = "app/src/main/java/example/Conversation.kt"
        val store = "app/src/main/java/other/MessageStore.kt"
        val resource = "app/src/main/res/values/strings.xml"
        val sources = linkedMapOf(controller to buildString {
            appendLine("package example\nclass Conversation {")
            repeat(2_000) { appendLine("fun conversation$it() = other.MessageStore.read(R.string.conversation)") }
            appendLine("}")
        }, store to "package other\nobject MessageStore { fun read(value: Int) = value }",
            resource to "<resources><string name=\"conversation\">Conversation</string></resources>")
        val inventory = RepositoryAnalysis.Inventory(sources.map { (path, text) ->
            val bytes = text.toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
            val sha = digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            RepositoryAnalysis.SourceFile(path, sha, bytes.size.toLong())
        }, false, "a".repeat(40))
        assertTrue(inventory.files.first().size in 100_000..RepositoryAnalysis.MAX_FILE_BYTES)
        val baseline = Debug.getNativeHeapAllocatedSize()
        val peak = AtomicLong(baseline)
        val sampling = AtomicBoolean(true)
        val ready = CountDownLatch(1)
        val sampler = Thread({
            ready.countDown()
            while (sampling.get()) {
                val used = Debug.getNativeHeapAllocatedSize()
                peak.updateAndGet { maxOf(it, used) }
                SystemClock.sleep(10)
            }
        }, "analysis-native-memory-sampler")
        sampler.start()
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        val started = SystemClock.elapsedRealtime()
        val plan = try {
            RepositoryAnalysis.analyze("Conversation message", inventory, sources::getValue)
        } finally {
            peak.updateAndGet { maxOf(it, Debug.getNativeHeapAllocatedSize()) }
            sampling.set(false)
            sampler.join(2_000)
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        val growth = peak.get() - baseline
        println("Dense source analysis: elapsed=$elapsed ms; peak native growth=$growth bytes")
        assertTrue("Native matching must not retain a source copy for every token", growth < 64L * 1_024 * 1_024)
        val file = plan.files.single { it.path == controller }
        assertTrue(file.dependencies.any { store in it.resolvedPaths })
        assertTrue(file.dependencies.any { resource in it.resolvedPaths })
        assertEquals(inventory.commitSha, plan.commitSha)
        assertEquals(sources.keys.toSet(), plan.inspectedPaths.toSet())
    }
}
