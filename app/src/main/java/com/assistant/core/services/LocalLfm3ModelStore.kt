package com.assistant.core.services

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

/** Exact, pre-reviewed GGUF repositories and filenames. No user-supplied model URL is executed. */
object LocalLfm3DownloadRules {
    const val MIN_SIZE = 600_000_000L
    const val MAX_SIZE = 3_000_000_000L
    private val SHA256 = Regex("[a-fA-F0-9]{64}")
    private val MODELS = Regex("[A-Za-z0-9_-]+/[A-Za-z0-9._-]+")
    private val GGUF = Regex("[A-Za-z0-9._-]+\\.gguf")

    data class Metadata(val bytes: Long, val sha256: String)
    fun source(variant: LocalLfm3Models.Variant): String {
        require(MODELS.matches(variant.repository) && GGUF.matches(variant.ggufFile))
        return "https://huggingface.co/${variant.repository}/resolve/main/${variant.ggufFile}"
    }
    fun sha256(linkedEtag: String?, etag: String?): String? =
        listOf(linkedEtag, etag).firstNotNullOfOrNull { raw ->
            raw?.trim()?.trim('"')?.takeIf(SHA256::matches)?.lowercase(Locale.ROOT)
        }

    fun metadata(bytes: String?, linkedBytes: String?, linkedEtag: String?, etag: String?): Metadata? {
        val expected = linkedBytes?.toLongOrNull()?.takeIf { it in MIN_SIZE..MAX_SIZE }
            ?: bytes?.toLongOrNull()?.takeIf { it in MIN_SIZE..MAX_SIZE }
            ?: return null
        val checksum = sha256(linkedEtag, etag) ?: return null
        return Metadata(expected, checksum)
    }
    private val CONTENT_RANGE = Regex("""bytes ([0-9]+)-([0-9]+)/([0-9]+)""")
    fun rangeAccepted(code: Int, header: String?, start: Long, size: Long): Boolean {
        if (code != 206 || start !in 1 until size) return false
        val match = CONTENT_RANGE.matchEntire(header?.trim() ?: return false) ?: return false
        val begin = match.groupValues[1].toLongOrNull() ?: return false
        val end = match.groupValues[2].toLongOrNull() ?: return false
        val total = match.groupValues[3].toLongOrNull() ?: return false
        return begin == start && end >= begin && end < size && total == size
    }

    fun progress(done: Long, total: Long) =
        if (total <= 0) 0 else ((done * 100) / total).toInt().coerceIn(0, 100)
}

/**
 * Personal-only GGUF storage and verified, resumable HTTPS downloads.
 * The native llama.cpp runtime is NOT part of this module; this stores weights for that milestone.
 * Blocking operations must be called on an app worker thread, never the main thread.
 */
class LocalLfm3ModelStore(private val context: Context) {
    private val root by lazy {
        File(context.noBackupFilesDir, "lfm3-models").apply { if (!isDirectory && !mkdirs()) throw IOException("Could not create private model directory") }
    }
    data class Snapshot(val exists: Boolean, val bytes: Long, val sha256: String?, val partialBytes: Long)
    data class Downloaded(val file: File, val bytes: Long, val sha256: String)
    class DownloadCancelled : IOException("Download paused. Resume from AI settings.")

    private fun prefix(variant: LocalLfm3Models.Variant) = variant.key.takeIf { key ->
        LocalLfm3Models.variants.any { it.key == key }
    } ?: throw IllegalArgumentException("Unsupported model profile")
    fun modelFile(variant: LocalLfm3Models.Variant): File = File(root, prefix(variant) + ".gguf")
    private fun stamp(variant: LocalLfm3Models.Variant) = File(root, prefix(variant) + ".verified")
    private fun partial(variant: LocalLfm3Models.Variant) = File(root, prefix(variant) + ".part")
    private fun partialStamp(variant: LocalLfm3Models.Variant) = File(root, prefix(variant) + ".part.sha256")
    private fun marked(file: File): String? = runCatching {
        file.takeIf { it.isFile && it.length() in 64..96 }?.readText()?.trim()
            ?.takeIf { Regex("[a-f0-9]{64}:[0-9]+").matches(it) }
    }.getOrNull()

    /** Quick state from the locally persisted post-download verification receipt. */
    @Synchronized fun status(variant: LocalLfm3Models.Variant): Snapshot {
        if (context.packageName != "com.assistant.core") return Snapshot(false, 0, null, 0)
        val file = modelFile(variant)
        val receipt = marked(stamp(variant))?.split(":")
        val verifiedSize = receipt?.getOrNull(1)?.toLongOrNull()
        val valid = file.isFile && verifiedSize == file.length() &&
            file.length() in LocalLfm3DownloadRules.MIN_SIZE..LocalLfm3DownloadRules.MAX_SIZE
        return Snapshot(valid, if (valid) file.length() else 0, if (valid) receipt?.firstOrNull() else null,
            partial(variant).takeIf(File::isFile)?.length() ?: 0L)
    }

    /** Hashes actual bytes when explicitly requested, not on the UI render path. */
    fun verify(variant: LocalLfm3Models.Variant, progress: (Int) -> Unit = {}): Boolean {
        requirePersonal()
        val file = modelFile(variant)
        val receipt = marked(stamp(variant))?.split(":") ?: return false
        val expectedSize = receipt.getOrNull(1)?.toLongOrNull() ?: return false
        if (!file.isFile || file.length() != expectedSize) return false
        return digest(file, expectedSize, progress) == receipt.first()
    }

    fun delete(variant: LocalLfm3Models.Variant): Boolean {
        requirePersonal()
        synchronized(this) {
            partial(variant).delete()
            partialStamp(variant).delete()
            stamp(variant).delete()
            return !modelFile(variant).exists() || modelFile(variant).delete()
        }
    }

    private fun requirePersonal() {
        check(context.packageName == "com.assistant.core") { "Local GGUF storage is for Personal J.A.R.V.I.S. only" }
    }

    private fun metadata(variant: LocalLfm3Models.Variant): LocalLfm3DownloadRules.Metadata {
        val conn = URL(LocalLfm3DownloadRules.source(variant)).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 12_000
            conn.readTimeout = 12_000
            conn.instanceFollowRedirects = false // Hugging Face supplies hash and size before CDN redirection.
            val status = conn.responseCode
            if (status !in 200..399) throw IOException("Model metadata unavailable (HTTP $status)")
            return LocalLfm3DownloadRules.metadata(
                conn.getHeaderField("Content-Length"),
                conn.getHeaderField("X-Linked-Size"),
                conn.getHeaderField("X-Linked-Etag"),
                conn.getHeaderField("ETag")
            ) ?: throw IOException("Model host did not provide a usable SHA-256 checksum and size")
        } finally { conn.disconnect() }
    }

    /**
     * Resume only when the partial file matches the new upstream digest.
     * Reject malformed ranges, incorrect sizes, non-HTTPS redirects, and mismatched SHA-256.
     */
    @Synchronized fun download(variant: LocalLfm3Models.Variant,
        cancelled: () -> Boolean = { false }, progress: (Int) -> Unit = {}): Downloaded {
        requirePersonal()
        if (cancelled()) throw DownloadCancelled()
        val info = metadata(variant)
        val existing = status(variant)
        if (existing.exists && existing.bytes == info.bytes && existing.sha256 == info.sha256) {
            if (verify(variant, progress)) return Downloaded(modelFile(variant), info.bytes, info.sha256)
        }
        val temp = partial(variant)
        val marker = partialStamp(variant)
        val signature = "${info.sha256}:${info.bytes}"
        if (marked(marker) != signature || !temp.isFile || temp.length() > info.bytes) {
            temp.delete()
            marker.writeText(signature)
        }
        val start = temp.length()
        if (root.usableSpace < (info.bytes - start + 200_000_000L))
            throw IOException("Not enough internal storage for model and 200 MB reserve")
        if (cancelled()) throw DownloadCancelled()
        val conn = URL(LocalLfm3DownloadRules.source(variant)).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            if (start > 0) conn.setRequestProperty("Range", "bytes=$start-")
            val status = conn.responseCode
            if (conn.url.protocol != "https") throw IOException("Model download redirected away from HTTPS")
            val append = LocalLfm3DownloadRules.rangeAccepted(status, conn.getHeaderField("Content-Range"), start, info.bytes)
            if (status !in 200..299 || status == 206 && !append)
                throw IOException("Model server returned invalid range/status (HTTP $status)")
            if (start > 0 && status != 200 && !append)
                throw IOException("Model resume was rejected")
            var received = if (append) start else 0L
            FileOutputStream(temp, append).use { output ->
                conn.inputStream.buffered(65_536).use { input ->
                    val buffer = ByteArray(65_536)
                    var lastReported = -1
                    while (true) {
                        if (cancelled()) throw DownloadCancelled()
                        val length = input.read(buffer)
                        if (length == -1) break
                        received += length
                        if (received > info.bytes) throw IOException("Model exceeded expected byte size")
                        output.write(buffer, 0, length)
                        val pct = LocalLfm3DownloadRules.progress(received, info.bytes)
                        if (pct != lastReported) { lastReported = pct; progress(pct) }
                    }
                    output.fd.sync()
                }
            }
            if (received != info.bytes || temp.length() != info.bytes)
                throw IOException("Incomplete GGUF transfer; download can be resumed")
            if (cancelled()) throw DownloadCancelled()
            if (digest(temp, info.bytes) { progress(it) } != info.sha256) {
                temp.delete(); marker.delete()
                throw IOException("GGUF SHA-256 check failed. Partial file discarded.")
            }
            val final = modelFile(variant)
            try {
                Files.move(temp.toPath(), final.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), final.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            stamp(variant).writeText(signature)
            marker.delete()
            progress(100)
            return Downloaded(final, info.bytes, info.sha256)
        } finally { conn.disconnect() }
    }

    private fun digest(file: File, total: Long, progress: (Int) -> Unit = {}): String {
        val sha = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        file.inputStream().buffered(65_536).use { source ->
            val buffer = ByteArray(65_536)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                sha.update(buffer, 0, read)
                bytes += read
                if (bytes % (32L * 1024 * 1024) < read)
                    progress(LocalLfm3DownloadRules.progress(bytes, total))
            }
        }
        return sha.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
