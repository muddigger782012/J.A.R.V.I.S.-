package com.jarvis.developer

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel

/** Bounded, no-follow, atomic metadata I/O shared by task plans and read-only repair bookmarks.
 * Callers own the read/compare/write transaction lock and their versioned data validation.
 */
internal class PrivateCheckpointFile(
    directory: File,
    name: String,
    private val minimum: Int,
    private val maximum: Int,
    private val atomicReplace: (Path, Path) -> Unit = { source, target ->
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    }
) {
    private val directory = directory.toPath().toAbsolutePath().normalize()
    private val checkpoint = this.directory.resolve(name)

    init {
        require(name.matches(Regex("[a-z-]+\\.bin")) && minimum > 0 && maximum >= minimum)
    }

    /** One read/compare/write transaction across UI, Binder workers and future process recovery. */
    fun <T> transaction(block: () -> T): T = synchronized(transactionLock) {
        ensureDirectory(create = true)
        val lock = directory.resolve(checkpoint.fileName.toString() + ".lock")
        require(!Files.isSymbolicLink(lock) && (!Files.exists(lock, LinkOption.NOFOLLOW_LINKS) ||
            Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS))) { "Private checkpoint lock is invalid." }
        FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            channel.lock().use { block() }
        }
    }

    companion object { private val transactionLock = Any() }

    fun read(): ByteArray? {
        if (!ensureDirectory(create = false) || !checkCheckpointType()) return null
        require(Files.size(checkpoint) in minimum.toLong()..maximum.toLong()) { "Private checkpoint size is invalid." }
        val output = ByteArrayOutputStream()
        val options = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        Files.newByteChannel(checkpoint, options).use { channel ->
            val buffer = ByteBuffer.allocate(8192)
            while (true) {
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                require(count <= maximum - output.size()) { "Private checkpoint exceeds its byte limit." }
                output.write(buffer.array(), 0, count)
                buffer.clear()
            }
        }
        return output.toByteArray().also {
            require(it.size >= minimum) { "Private checkpoint is truncated." }
        }
    }

    fun write(bytes: ByteArray) {
        require(bytes.size in minimum..maximum) { "Private checkpoint size is invalid." }
        ensureDirectory(create = true)
        checkCheckpointType()
        val temporary = Files.createTempFile(directory, ".private-checkpoint-", ".tmp")
        try {
            require(!Files.isSymbolicLink(temporary) && Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)) {
                "Private checkpoint temporary file is invalid."
            }
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            ensureDirectory(create = false)
            checkCheckpointType()
            // No non-atomic fallback, stale backup, or implicit recovery.
            atomicReplace(temporary, checkpoint)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun ensureDirectory(create: Boolean): Boolean {
        var current = directory.root
        for (part in directory) {
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current)) { "Private storage cannot use symbolic links." }
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                require(Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) { "Private storage path is not a directory." }
            }
        }
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) return false
            Files.createDirectories(directory)
            return ensureDirectory(create = false)
        }
        return true
    }

    private fun checkCheckpointType(): Boolean {
        require(!Files.isSymbolicLink(checkpoint)) { "Private checkpoint cannot be a symbolic link." }
        if (!Files.exists(checkpoint, LinkOption.NOFOLLOW_LINKS)) return false
        require(Files.isRegularFile(checkpoint, LinkOption.NOFOLLOW_LINKS)) { "Private checkpoint is not a regular file." }
        return true
    }
}
