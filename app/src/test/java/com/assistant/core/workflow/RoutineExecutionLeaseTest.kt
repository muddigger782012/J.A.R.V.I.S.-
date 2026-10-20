package com.assistant.core.workflow

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class RoutineExecutionLeaseTest {
    @Test fun independentProcessExcludesDispatchAndDeathReleasesOwnership() {
        val directory = Files.createTempDirectory("routine-lease").toFile()
        val classpath = listOf(RoutineLeaseChild::class.java, RoutineExecutionLease::class.java, kotlin.Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
        val child = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
            "-cp", classpath, RoutineLeaseChild::class.java.name, directory.path).redirectErrorStream(true).start()
        try {
            // Avoid an unbounded read if the child cannot load its test classes.
            val handshake = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val line = handshake.submit<String> { child.inputStream.bufferedReader().readLine() }
                assertEquals("OWNED", line.get(5, TimeUnit.SECONDS))
            } finally { handshake.shutdownNow() }
            val second = RoutineExecutionLease(directory)
            assertNull(second.tryExclusive { "must not dispatch" })
            assertTrue(child.isAlive)
            child.destroyForcibly()
            assertTrue(child.waitFor(5, TimeUnit.SECONDS))
            assertEquals("recovered", second.tryExclusive { "recovered" })
            assertEquals("released", RoutineExecutionLease(directory).tryExclusive { "released" })
        } finally {
            child.destroyForcibly()
            child.waitFor(5, TimeUnit.SECONDS)
            directory.deleteRecursively()
        }
    }

    @Test fun secondInstanceCannotReenterAndExceptionsReleaseTheLease() {
        val directory = Files.createTempDirectory("routine-lease").toFile()
        try {
            val first = RoutineExecutionLease(directory)
            val second = RoutineExecutionLease(directory)
            assertEquals("owned", first.tryExclusive {
                assertNull(second.tryExclusive { "duplicate" })
                "owned"
            })
            try { second.tryExclusive { error("operation failed") }; fail("Must propagate") } catch (_: IllegalStateException) { }
            assertEquals("available", first.tryExclusive { "available" })
        } finally { directory.deleteRecursively() }
    }
}

/** A genuinely separate JVM; no shared static lock table can make this test pass. */
object RoutineLeaseChild {
    @JvmStatic fun main(args: Array<String>) {
        RoutineExecutionLease(File(args.single())).tryExclusive {
            println("OWNED")
            System.out.flush()
            System.`in`.read()
            true
        } ?: error("Could not acquire child lease")
    }
}
