package com.assistant.core.services

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Small local documents. Every read/modify/write uses fresh disk state across app processes. */
class PersonalStateFile(private val directory: File, private val name: String) {
    constructor(context: Context, name: String) : this(File(context.applicationContext.noBackupFilesDir, "personal-planning"), name)
    init { require(Regex("[a-z-]+\\.json").matches(name)) }

    class Document internal constructor(private val file: File) {
        fun read(): String? {
            if (!file.exists()) return null
            return file.inputStream().use { stream ->
                val bytes = ByteArray(MAX_BYTES + 1)
                var count = 0
                while (count < bytes.size) {
                    val length = stream.read(bytes, count, bytes.size - count)
                    if (length < 0) break
                    count += length
                }
                check(count <= MAX_BYTES) { "Personal state exceeds its storage limit." }
                String(bytes, 0, count, Charsets.UTF_8)
            }
        }

        fun write(value: String?): Boolean {
            val temporary = File(file.path + ".tmp")
            if (value == null) {
                if (file.exists() && !file.delete()) return false
                if (temporary.exists() && !temporary.delete()) return false
                return !file.exists() && !temporary.exists()
            }
            val bytes = value.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_BYTES) return false
            FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return read() == value
        }
    }

    fun <T> access(block: Document.() -> T): T = synchronized(lock) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("Personal storage unavailable")
        RandomAccessFile(File(directory, "$name.lock"), "rw").use { guard ->
            guard.channel.lock().use { Document(File(directory, name)).block() }
        }
    }

    private companion object { val lock = Any(); const val MAX_BYTES = 256_000 }
}
