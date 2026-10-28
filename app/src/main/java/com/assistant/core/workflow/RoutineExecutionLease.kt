package com.assistant.core.workflow

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** Nonblocking ownership; the OS releases it on process death. Never delete the lock file. */
internal class RoutineExecutionLease(directory: File) {
    private val directory = directory.canonicalFile
    private val processLease = processLeases.computeIfAbsent(this.directory.path) { ReentrantLock() }

    fun <T : Any> tryExclusive(block: () -> T): T? {
        // Closing a competing descriptor can release an existing process file lock on some
        // platforms. Check JVM ownership first, before opening the shared lock file.
        if (processLease.isHeldByCurrentThread || !processLease.tryLock()) return null
        try {
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("Routine storage unavailable")
            return RandomAccessFile(File(directory, "execution.lock"), "rw").use { guard ->
                val lease = try { guard.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lease == null) null else lease.use { block() }
            }
        } finally {
            processLease.unlock()
        }
    }

    private companion object {
        val processLeases = ConcurrentHashMap<String, ReentrantLock>()
    }
}
