package com.assistant.core.services

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/** Java-only network seam: tests can exercise redirects, headers and broken streams without Android. */
class UpdateHttpTransport(
    private val accessToken: String? = null,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val nanoTime: () -> Long = System::nanoTime
) {
    fun fetchMetadata(channel: UpdateChannel): String {
        val deadline = nanoTime() + 60L * 1_000_000_000
        return open(channel.metadataUrl, channel, null).use { response ->
            if (response.contentLength > UpdatePolicy.MAX_METADATA_BYTES) {
                throw UpdateException(UpdateFailureCode.INVALID_METADATA, "The update manifest is too large.")
            }
            val bytes = response.stream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    checkRunning(deadline)
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (output.size() + count > UpdatePolicy.MAX_METADATA_BYTES) {
                        throw UpdateException(UpdateFailureCode.INVALID_METADATA, "The update manifest is too large.")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            bytes.toString(Charsets.UTF_8)
        }
    }

    /** A partial file is never advertised as installable and is removed on every unsuccessful path. */
    fun download(candidate: UpdateCandidate, target: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val artifact = candidate.artifact
        if (!UpdatePolicy.isArtifactUrl(artifact.url, candidate.metadata.channel, artifact.abi) ||
            artifact.sizeBytes !in 1..UpdatePolicy.MAX_APK_BYTES) {
            throw UpdateException(UpdateFailureCode.INVALID_METADATA, "The selected APK source or size is invalid.")
        }
        val deadline = nanoTime() + 30L * 60 * 1_000_000_000
        var succeeded = false
        try {
            open(artifact.url, candidate.metadata.channel, artifact.abi).use { response ->
                if (response.contentLength >= 0 && response.contentLength != artifact.sizeBytes) {
                    throw UpdateException(UpdateFailureCode.DOWNLOAD_INTEGRITY, "The download size differs from the update manifest.")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                response.stream.use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkRunning(deadline)
                            val count = input.read(buffer)
                            if (count == -1) break
                            downloaded += count
                            if (downloaded > artifact.sizeBytes || downloaded > UpdatePolicy.MAX_APK_BYTES) {
                                throw UpdateException(UpdateFailureCode.DOWNLOAD_INTEGRITY, "The downloaded APK exceeds its advertised size.")
                            }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            onProgress(downloaded, artifact.sizeBytes)
                        }
                        output.fd.sync()
                    }
                }
                if (downloaded != artifact.sizeBytes || digest.digest().hex() != artifact.sha256) {
                    throw UpdateException(UpdateFailureCode.DOWNLOAD_INTEGRITY, "The APK download is incomplete or failed its SHA-256 integrity check. Please try again.")
                }
            }
            succeeded = true
        } finally {
            if (!succeeded) target.delete()
        }
    }

    private fun open(originalUrl: String, channel: UpdateChannel, abi: String?): Response {
        val valid = if (abi == null) UpdatePolicy.isMetadataUrl(originalUrl, channel)
            else UpdatePolicy.isArtifactUrl(originalUrl, channel, abi)
        if (!valid) throw UpdateException(UpdateFailureCode.INVALID_METADATA, "The update source is not approved.")
        val token = accessToken?.trim().orEmpty()
        if (token.any { it.isISOControl() }) {
            throw UpdateException(UpdateFailureCode.NETWORK, "The saved GitHub token contains invalid characters.")
        }
        var requestUrl = originalUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            var connection: HttpURLConnection? = null
            var handedOff = false
            try {
                connection = openConnection(URL(requestUrl))
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "JARVIS-Updater/3")
                connection.setRequestProperty("Accept", if (abi == null) "application/json" else "application/octet-stream")
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (token.isNotEmpty() && UpdatePolicy.authorizationAllowed(requestUrl, originalUrl)) {
                    connection.setRequestProperty("Authorization", "Bearer $token")
                }
                val status = connection.responseCode
                if (status in REDIRECT_CODES) {
                    if (redirectCount >= MAX_REDIRECTS) throw UpdateException(UpdateFailureCode.NETWORK, "The update source redirected too many times.")
                    val location = connection.getHeaderField("Location")
                        ?: throw UpdateException(UpdateFailureCode.NETWORK, "The update source returned an invalid redirect.")
                    val next = runCatching { URI(requestUrl).resolve(location).toASCIIString() }.getOrNull()
                        ?: throw UpdateException(UpdateFailureCode.NETWORK, "The update source returned an invalid redirect.")
                    if (!UpdatePolicy.isAllowedRedirect(next, originalUrl, channel, abi)) {
                        throw UpdateException(UpdateFailureCode.NETWORK, "The update source redirected to an unapproved location.")
                    }
                    requestUrl = next
                } else {
                    if (status != HttpURLConnection.HTTP_OK) {
                        val code = if (abi == null && status == 404) UpdateFailureCode.METADATA_NOT_FOUND else UpdateFailureCode.NETWORK
                        val message = when {
                            code == UpdateFailureCode.METADATA_NOT_FOUND -> "This update channel has not been published yet. Your installed app has been kept."
                            status == 401 || status == 403 -> "GitHub denied update access. Check repository permissions or the saved token."
                            else -> "The update source returned HTTP $status. Please try again later."
                        }
                        throw UpdateException(code, message)
                    }
                    val stream = connection.inputStream
                    handedOff = true
                    return Response(stream, connection.contentLengthLong, connection)
                }
            } catch (error: UpdateException) {
                throw error
            } catch (_: Exception) {
                throw UpdateException(UpdateFailureCode.NETWORK, "The update could not be downloaded. Check your connection and try again.")
            } finally {
                if (!handedOff) connection?.disconnect()
            }
        }
        throw UpdateException(UpdateFailureCode.NETWORK, "The update source redirected too many times.")
    }

    private fun checkRunning(deadline: Long) {
        if (Thread.currentThread().isInterrupted) {
            throw UpdateException(UpdateFailureCode.CANCELLED, "The update download was cancelled.")
        }
        if (nanoTime() >= deadline) {
            throw UpdateException(UpdateFailureCode.NETWORK, "The update download timed out. Please try again.")
        }
    }

    private class Response(val stream: InputStream, val contentLength: Long, val connection: HttpURLConnection) : Closeable {
        override fun close() {
            try { stream.close() } finally { connection.disconnect() }
        }
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
