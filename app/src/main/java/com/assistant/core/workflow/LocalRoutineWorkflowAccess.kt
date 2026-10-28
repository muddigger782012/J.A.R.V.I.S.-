package com.assistant.core.workflow

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile

/** Private, non-backed-up checkpoints shared by typed chat and the :wake process. */
class LocalRoutineWorkflowAccess(directory: File) : RoutineWorkflowAccess {
    constructor(context: Context) : this(File(context.applicationContext.noBackupFilesDir, "routine-execution"))

    private val directory = directory.canonicalFile
    private val executionLease = RoutineExecutionLease(this.directory)
    private val journalBase = File(this.directory, "checkpoints.json")
    private val journal = AtomicFile(journalBase)
    private val cancellationBase = File(this.directory, "cancel-request")
    private val cancellation = AtomicFile(cancellationBase)
    override val store: RoutineWorkflowStore = JsonRoutineWorkflowStore(
        { io { read(journal, journalBase, MAX_JOURNAL_BYTES) } },
        { value -> io { write(journal, journalBase, value, MAX_JOURNAL_BYTES) } }
    )

    override fun <T : Any> tryExclusive(block: () -> T): T? = executionLease.tryExclusive(block)

    override fun cancellationRequested(id: String): Boolean = io {
        read(cancellation, cancellationBase, MAX_CANCEL_BYTES)?.let { value ->
            require(value.isNotBlank() && value.length <= MAX_CANCEL_BYTES) { "Invalid cancellation checkpoint." }
            value == id
        } ?: false
    }

    override fun requestCancellation(id: String): Boolean = io {
        require(id.isNotBlank() && id.length <= MAX_CANCEL_BYTES)
        val saved = JsonRoutineWorkflowStore({ read(journal, journalBase, MAX_JOURNAL_BYTES) }, { false })
            .load().firstOrNull { it.id == id } ?: return@io false
        // A stale control cannot overwrite the cancellation of a newer invocation.
        if (saved.status in setOf(RoutineWorkflowStatus.CANCELLED, RoutineWorkflowStatus.VERIFIED,
                RoutineWorkflowStatus.USER_CONFIRMED)) true
        else write(cancellation, cancellationBase, id, MAX_CANCEL_BYTES)
    }

    private fun prepare() {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("Routine storage unavailable")
    }

    private fun <T> io(block: () -> T): T = synchronized(ioLock) {
        prepare()
        RandomAccessFile(File(directory, "storage.lock"), "rw").use { guard ->
            guard.channel.lock().use { block() }
        }
    }

    private fun read(file: AtomicFile, base: File, maximum: Int): String? = try {
        file.openRead().use { stream ->
            val bytes = ByteArray(maximum + 1)
            var count = 0
            while (count < bytes.size) {
                val length = stream.read(bytes, count, bytes.size - count)
                if (length < 0) break
                count += length
            }
            check(count <= maximum) { "Routine checkpoint exceeds its storage limit." }
            String(bytes, 0, count, Charsets.UTF_8)
        }
    } catch (error: FileNotFoundException) {
        // An unreadable existing journal must never become an empty, repeatable history.
        if (!base.exists() && !File(base.path + ".bak").exists() && !File(base.path + ".new").exists()) null else throw error
    }

    private fun write(file: AtomicFile, base: File, value: String, maximum: Int): Boolean {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > maximum) return false
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        return read(file, base, maximum) == value
    }

    private companion object {
        val ioLock = Any()
        const val MAX_JOURNAL_BYTES = 2_000_000
        const val MAX_CANCEL_BYTES = 200
    }
}
