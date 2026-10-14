package com.assistant.core.services

import android.content.Context
import org.json.JSONObject
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class NeuralVoicePack(context: Context) {
    private val app = context.applicationContext
    val root = File(app.filesDir, "kokoro-en-v019")
    private fun metadata() = JSONObject(app.assets.open("neural-voice-pack.json").bufferedReader().use { it.readText() }).also {
        require(it.getString("url") == SOURCE)
        require(Regex("[a-f0-9]{64}").matches(it.getString("sha256"))) { "Voice download metadata is absent from this build" }
        require(it.getLong("bytes") in 1..MAX_BYTES)
        require(it.getLong("unpacked_bytes") in 1..MAX_BYTES)
    }
    fun bundled() = com.assistant.core.BuildConfig.BUNDLED_NEURAL_VOICES
    fun available() = runCatching { metadata(); true }.getOrDefault(false)
    fun installed() = runCatching {
        val meta = metadata()
        File(root, ".ready").readText() == meta.getString("sha256") &&
            listOf("model.onnx", "voices.bin", "tokens.txt", "LICENSE").all { File(root, it).length() > 0 } &&
            File(root, "espeak-ng-data").isDirectory
    }.getOrDefault(false)
    fun downloadSizeMb() = runCatching { metadata().getLong("bytes") / 1048576 }.getOrDefault(0)

    /** Beta reads its signed APK asset; other variants download only after a user request. */
    fun install(cancelled: AtomicBoolean, status: (String) -> Unit) = synchronized(installLock) {
        RandomAccessFile(File(app.filesDir, "neural-voice-install.lock"), "rw").use { lock ->
            lock.channel.lock().use {
                val backup = File(app.filesDir, "neural-voice-backup")
                // Recover an interrupted swap before inspecting the ready marker.
                if (!root.exists() && backup.exists()) Files.move(backup.toPath(), root.toPath(), StandardCopyOption.ATOMIC_MOVE)
                if (installed()) { backup.deleteRecursively(); return@synchronized }
                val meta = metadata()
                require(app.filesDir.usableSpace > meta.getLong("bytes") + meta.getLong("unpacked_bytes") + 32 * 1048576L) {
                    "Not enough free storage to prepare voices. Free at least ${(meta.getLong("bytes") + meta.getLong("unpacked_bytes") + 32 * 1048576L) / 1048576 + 1} MB and retry."
                }
                val archive = File(app.filesDir, "neural-voice-download.part")
                val stage = File(app.filesDir, "neural-voice-stage")
                stage.deleteRecursively(); stage.mkdirs()
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var downloaded = 0L
                    var lastPercent = -1
                    (if (bundled()) app.assets.open(BUNDLED_ASSET) else download()).use { input ->
                        archive.outputStream().buffered().use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                check(!cancelled.get()) { "Voice preparation cancelled" }
                                val count = input.read(buffer)
                                if (count < 0) break
                                downloaded += count
                                check(downloaded <= meta.getLong("bytes")) { "Unexpected voice download size" }
                                digest.update(buffer, 0, count); output.write(buffer, 0, count)
                                val percent = (downloaded * 100 / meta.getLong("bytes")).toInt()
                                if (percent != lastPercent) { status(if (bundled()) "Preparing bundled voices: $percent%" else "Downloading voices: $percent%"); lastPercent = percent }
                            }
                        }
                    }
                    check(downloaded == meta.getLong("bytes")) { "Voice download is incomplete" }
                    val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    check(hash == meta.getString("sha256")) { "Voice download integrity check failed" }
                    status("Installing verified voice pack…")
                    var unpacked = 0L
                    var lastInstallPercent = -1
                    archive.inputStream().buffered().use { source ->
                        TarArchiveInputStream(BZip2CompressorInputStream(source)).use { tar ->
                            while (true) {
                                check(!cancelled.get()) { "Voice preparation cancelled" }
                                val entry = tar.nextTarEntry ?: break
                                check(entry.isDirectory || entry.isFile) { "Unsupported voice archive entry" }
                                val target = NeuralVoiceCatalog.safeDestination(stage, entry.name)
                                if (entry.isDirectory) { target.mkdirs(); continue }
                                unpacked += entry.size
                                check(unpacked <= meta.getLong("unpacked_bytes")) { "Voice archive size mismatch" }
                                target.parentFile?.mkdirs()
                                target.outputStream().buffered().use { output ->
                                    val buffer = ByteArray(65536)
                                    var written = 0L
                                    while (true) {
                                        check(!cancelled.get()) { "Voice preparation cancelled" }
                                        val count = tar.read(buffer)
                                        if (count < 0) break
                                        output.write(buffer, 0, count); written += count
                                        val percent = ((unpacked - entry.size + written) * 100 / meta.getLong("unpacked_bytes")).toInt()
                                        if (percent != lastInstallPercent) { status("Installing voices: $percent%"); lastInstallPercent = percent }
                                    }
                                    check(written == entry.size) { "Voice archive is incomplete" }
                                }
                            }
                        }
                    }
                    check(unpacked == meta.getLong("unpacked_bytes"))
                    check(listOf("model.onnx", "voices.bin", "tokens.txt", "LICENSE").all { File(stage, it).length() > 0 })
                    check(File(stage, "espeak-ng-data").isDirectory)
                    check(!cancelled.get()) { "Voice preparation cancelled" }
                    File(stage, ".ready").writeText(hash)
                    backup.deleteRecursively()
                    if (root.exists()) Files.move(root.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    try {
                        Files.move(stage.toPath(), root.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    } catch (error: Throwable) {
                        if (!root.exists() && backup.exists()) Files.move(backup.toPath(), root.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        throw error
                    }
                    backup.deleteRecursively()
                    status("Offline voices installed")
                } finally { archive.delete(); stage.deleteRecursively() }
            }
        }
    }

    private fun download(): java.io.InputStream {
        var url = URL(SOURCE)
        repeat(5) {
            require(url.protocol == "https" && url.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com"))
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false; connection.connectTimeout = 15000; connection.readTimeout = 20000
            val code = connection.responseCode
            if (code in 300..399) {
                val next = connection.getHeaderField("Location") ?: error("Voice download redirect is missing")
                connection.disconnect(); url = URL(url, next)
            } else {
                if (code != 200) { connection.disconnect(); error("Voice download failed (HTTP $code)") }
                return object : java.io.FilterInputStream(connection.inputStream) {
                    override fun close() { try { super.close() } finally { connection.disconnect() } }
                }
            }
        }
        error("Too many voice download redirects")
    }
    companion object {
        const val BUNDLED_ASSET = "voice-packs/kokoro.tar.bz2"
        const val SOURCE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2"
        private const val MAX_BYTES = 1024 * 1024 * 1024L
        private val installLock = Any()
    }
}
