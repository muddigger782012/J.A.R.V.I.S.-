package com.jarvis.developer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest

/** Private bookmarks, never proof of an approved proposal or a successful repair.
 * Only product, task-branch and commit identity survive. No source, diff, scope, prompt,
 * provider configuration, token, build result or approval is serialized.
 */
class RepairHistoryStore internal constructor(directory: File, atomicReplace: ((Path, Path) -> Unit)?) {
    constructor(directory: File) : this(directory, null)

    data class Bookmark(val target: PatchEngine.Target, val branch: String, val commitSha: String)

    private val file = if (atomicReplace == null) PrivateCheckpointFile(directory, FILE_NAME, HEADER_BYTES, MAX_BYTES)
        else PrivateCheckpointFile(directory, FILE_NAME, HEADER_BYTES, MAX_BYTES, atomicReplace)

    fun load(): List<Bookmark> = synchronized(lock) { read() }

    /** Most recently explicitly saved first; retain only ten unique commit bookmarks. */
    fun save(value: Bookmark): Unit = synchronized(lock) {
        validate(value)
        val updated = (listOf(value) + read().filter { it != value }).take(MAX_ENTRIES)
        file.write(encode(updated))
    }

    /** Explicit recovery is also allowed for a damaged regular file, never for a symlink. */
    fun clear(): Unit = synchronized(lock) { file.write(encode(emptyList())) }

    private fun read(): List<Bookmark> {
        val bytes = file.read() ?: return emptyList()
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val magic = ByteArray(8).also(input::readFully)
            require(magic.contentEquals(MAGIC) && input.readInt() == 1) { "Repair history format is unsupported." }
            val expectedHash = ByteArray(32).also(input::readFully)
            val payload = ByteArray(input.available()).also(input::readFully)
            require(MessageDigest.isEqual(expectedHash, hash(payload))) { "Repair history checksum does not match." }
            DataInputStream(ByteArrayInputStream(payload)).use { data ->
                val count = data.readInt()
                require(count in 0..MAX_ENTRIES) { "Repair history count is invalid." }
                val values = (0 until count).map {
                    val target = when (data.readUnsignedByte()) {
                        0 -> PatchEngine.Target.DEVELOPER
                        1 -> PatchEngine.Target.PERSONAL
                        else -> error("Repair history product is invalid.")
                    }
                    Bookmark(target, data.readUTF(), data.readUTF()).also(::validate)
                }
                require(data.read() == -1 && values.distinct().size == values.size) { "Repair history has extra or duplicate data." }
                java.util.Collections.unmodifiableList(values)
            }
        }
    }

    private fun encode(values: List<Bookmark>): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { data ->
            data.writeInt(values.size)
            for (value in values) {
                validate(value)
                data.writeByte(if (value.target == PatchEngine.Target.PERSONAL) 1 else 0)
                data.writeUTF(value.branch)
                data.writeUTF(value.commitSha)
            }
        }
        val payload = buffer.toByteArray()
        val envelope = ByteArrayOutputStream()
        DataOutputStream(envelope).use { data ->
            data.write(MAGIC)
            data.writeInt(1)
            data.write(hash(payload))
            data.write(payload)
        }
        return envelope.toByteArray()
    }

    companion object {
        private val lock = Any()
        private const val FILE_NAME = "repair-history.bin"
        private const val HEADER_BYTES = 48
        private const val MAX_BYTES = 8192
        private const val MAX_ENTRIES = 10
        private val MAGIC = "JARVRH01".toByteArray(Charsets.US_ASCII)
        private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)

        fun bookmark(result: PatchPublisher.CommitResult): Bookmark {
            PatchPublisher.validateResult(result)
            return Bookmark(result.target, result.branch, result.commitSha).also(::validate)
        }

        fun validate(value: Bookmark) {
            val prefix = if (value.target == PatchEngine.Target.PERSONAL) "feature/jarvis-personal-repair/"
                else "feature/jarvis-developer-task/"
            require(value.commitSha.matches(Regex("[a-f0-9]{40}")) &&
                value.branch.matches(Regex(Regex.escape(prefix) + "[A-Za-z0-9][A-Za-z0-9_-]{0,47}-[a-f0-9]{12}")) &&
                BuildDiagnostics.validBranch(value.branch, value.target)) { "Repair history identity is invalid." }
        }
    }
}
