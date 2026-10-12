package com.assistant.core.services

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32

/** Only explicitly cacheable phrases enter this bounded private PCM cache. */
class NeuralSpeechCache(private val directory: File, private val maxBytes: Long = 8 * 1024 * 1024L) {
    data class Clip(val sampleRate: Int, val samples: FloatArray)
    fun key(model: String, voice: String, text: String): String = MessageDigest.getInstance("SHA-256")
        .digest("pcm-v1\u0000$model\u0000$voice\u00001.0\u0000$text".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun file(key: String): File {
        require(Regex("[a-f0-9]{64}").matches(key))
        return File(directory, "$key.pcm")
    }
    fun read(key: String): Clip? = runCatching {
        val file = file(key)
        if (!file.isFile || file.length() !in 20..MAX_FILE_BYTES.toLong()) return null
        val bytes = file.readBytes()
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(data.int == MAGIC)
        val rate = data.int; val count = data.int; val crc = data.int.toLong() and 0xffffffffL
        check(rate in 8000..48000 && count in 1..MAX_SAMPLES && bytes.size == 16 + count * 4)
        check(CRC32().apply { update(bytes, 16, bytes.size - 16) }.value == crc)
        val samples = FloatArray(count) { data.float }
        check(samples.all { it.isFinite() })
        file.setLastModified(System.currentTimeMillis())
        Clip(rate, samples)
    }.getOrNull()
    fun write(key: String, clip: Clip) {
        require(clip.sampleRate in 8000..48000 && clip.samples.size in 1..MAX_SAMPLES && clip.samples.all { it.isFinite() })
        val target = file(key)
        directory.mkdirs()
        directory.listFiles().orEmpty().filter { it.name.endsWith(".tmp") && it.lastModified() < System.currentTimeMillis() - 3600000L }
            .forEach { it.delete() }
        val bytes = ByteArray(16 + clip.samples.size * 4)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        data.putInt(MAGIC).putInt(clip.sampleRate).putInt(clip.samples.size).putInt(0)
        clip.samples.forEach { data.putFloat(it) }
        data.putInt(12, CRC32().apply { update(bytes, 16, bytes.size - 16) }.value.toInt())
        val temporary = File(directory, "${UUID.randomUUID()}.tmp")
        try {
            temporary.writeBytes(bytes)
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            var total = files().sumOf { it.length() }
            for (old in files().sortedBy { it.lastModified() }) {
                if (total <= maxBytes) break
                val size = old.length()
                if (old.delete()) total -= size
            }
        } finally { temporary.delete() }
    }
    private fun files() = directory.listFiles().orEmpty().filter { Regex("[a-f0-9]{64}\\.pcm").matches(it.name) }
    fun bytes() = files().sumOf { it.length() }
    fun clear() = files().forEach { it.delete() }
    companion object {
        private const val MAGIC = 0x4a50434d
        private const val MAX_SAMPLES = 480000
        private const val MAX_FILE_BYTES = 16 + MAX_SAMPLES * 4
    }
}
