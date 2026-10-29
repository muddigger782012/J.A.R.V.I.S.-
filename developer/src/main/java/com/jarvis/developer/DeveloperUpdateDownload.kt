package com.jarvis.developer

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.util.Timer
import java.util.TimerTask

/** Public, fixed-channel update evidence, not a build/test receipt or installation authority. */
internal object DeveloperUpdateRelease {
    const val API = "https://api.github.com/repos/muddigger782012/J.A.R.V.I.S.-/releases/tags/developer-latest"
    const val APK = "https://github.com/muddigger782012/J.A.R.V.I.S.-/releases/download/developer-latest/Jarvis-Developer.apk"
    const val PACKAGE = "com.jarvis.developer"
    const val SIGNER = "93a2f2031692f947440f4cfd874a166609cd2735eeda28e60904da64e8245790"
    const val MAX_APK = 64L * 1024 * 1024
    const val MAX_METADATA = 256L * 1024
    const val MAX_MILLIS = 180_000L
    data class Release(val releaseId: Long, val assetId: Long, val source: String, val version: Long,
        val bytes: Long, val sha256: String) {
        val versionName: String get() = "0.1.$version"
    }
    enum class Problem { NETWORK, NOT_PUBLISHED, RATE_LIMIT, METADATA, LIMIT, CHANGED, CURRENT, CANCELLED, STORAGE }
    class Failure(val problem: Problem) : IllegalStateException(problem.name)
    fun validate(r: Release) {
        require(r.releaseId > 0 && r.assetId > 0 && r.source.matches(Regex("[a-f0-9]{40}")))
        require(r.version in 1..2_099_999_999 && r.bytes in 1..MAX_APK && r.sha256.matches(Regex("[a-f0-9]{64}")))
    }
    fun parse(bytes: ByteArray): Release = try {
        require(bytes.size.toLong() in 1..MAX_METADATA)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        BoundedJsonGrammar(text).validate()
        val root = JSONObject(text)
        require(root.get("draft") == false && root.get("prerelease") == true && root.getString("tag_name") == "developer-latest")
        val name = root.getString("name")
        require(name.matches(Regex("Jarvis Developer 0\\.1\\.[1-9][0-9]{0,9}")))
        val version = name.removePrefix("Jarvis Developer 0.1.").toLong()
        val assets = root.getJSONArray("assets"); require(assets.length() in 1..128)
        val choices = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name") == "Jarvis-Developer.apk" }
        require(choices.size == 1)
        val apk = choices.single()
        require(apk.getString("browser_download_url") == APK && apk.getString("digest").matches(Regex("sha256:[a-f0-9]{64}")))
        Release(DevelopmentArtifact.number(root, "id"), DevelopmentArtifact.number(apk, "id"),
            root.getString("target_commitish"), version, DevelopmentArtifact.number(apk, "size"),
            apk.getString("digest").removePrefix("sha256:")).also(::validate)
    } catch (_: Exception) { throw Failure(Problem.METADATA) }
    fun explain(error: Throwable): String = when ((error as? Failure)?.problem) {
        Problem.NOT_PUBLISHED -> "The Developer update channel is unavailable. Keep the installed app and check again later."
        Problem.RATE_LIMIT -> "GitHub denied or limited this public update check. Check connectivity and wait before retrying."
        Problem.NETWORK -> "The update connection was interrupted. Check connectivity and explicitly retry."
        Problem.METADATA -> "The published update metadata could not be verified. Keep the installed app and check the release."
        Problem.LIMIT -> "The update exceeds its size or time limits. No installation was requested."
        Problem.CHANGED -> "The published update changed during review. Check for updates again before installing."
        Problem.CURRENT -> "The installed Developer app is already current or newer. No additional installation was requested."
        Problem.CANCELLED -> "Update cancelled. No installation was requested."
        Problem.STORAGE -> "The downloaded update is unavailable or changed. Check for updates again."
        else -> if (error is DevelopmentArtifact.Failure) DevelopmentArtifact.explain(error)
            else "The update could not be verified. Keep the installed app and check again."
    }
}

internal interface DeveloperUpdateSource {
    fun latest(): DeveloperUpdateRelease.Release
    fun download(release: DeveloperUpdateRelease.Release): File
    fun verify(file: File, release: DeveloperUpdateRelease.Release)
}

/** No authenticated request, automatic redirect, arbitrary URL, install call or shared download filename. */
internal class DeveloperUpdateDownload(
    private val cache: File,
    private val inspect: (File, DeveloperUpdateRelease.Release) -> Unit,
    private val cancellation: PatchPreviewCancellation,
    private val transport: BuildFeedback.Transport = http(cancellation),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) : DeveloperUpdateSource {
    private var started = 0L
    private var requests = 0
    private fun begin() { started = clock(); requests = 0; check() }
    private fun check() {
        if (cancellation.isCancelled) throw DeveloperUpdateRelease.Failure(DeveloperUpdateRelease.Problem.CANCELLED)
        if (clock() - started !in 0 until DeveloperUpdateRelease.MAX_MILLIS)
            throw DeveloperUpdateRelease.Failure(DeveloperUpdateRelease.Problem.LIMIT)
    }
    private fun request(url: String): BuildFeedback.Response {
        check(); require(++requests <= 5); requireUrl(url)
        return transport.get(url, null, DeveloperUpdateRelease.MAX_MILLIS - (clock() - started)).also { response ->
            response.watch(cancellation)
            if (response.finalUrl != url) { response.close(); throw DeveloperUpdateRelease.Failure(DeveloperUpdateRelease.Problem.METADATA) }
        }
    }
    private fun status(code: Int, vararg allowed: Int) {
        if (code !in allowed) throw DeveloperUpdateRelease.Failure(when (code) {
            404 -> DeveloperUpdateRelease.Problem.NOT_PUBLISHED
            403, 429 -> DeveloperUpdateRelease.Problem.RATE_LIMIT
            else -> DeveloperUpdateRelease.Problem.NETWORK
        })
    }
    private fun copy(response: BuildFeedback.Response, output: java.io.OutputStream, maximum: Long): Long {
        if (response.contentLength > maximum) throw DeveloperUpdateRelease.Failure(DeveloperUpdateRelease.Problem.LIMIT)
        var total = 0L; val buffer = ByteArray(64 * 1024)
        while (true) {
            check(); val n = response.body.read(buffer); if (n < 0) break
            total += n
            if (total > maximum) throw DeveloperUpdateRelease.Failure(DeveloperUpdateRelease.Problem.LIMIT)
            output.write(buffer, 0, n)
        }
        check(); require(response.contentLength < 0 || total == response.contentLength)
        return total
    }
    private fun readRelease(): DeveloperUpdateRelease.Release = request(DeveloperUpdateRelease.API).use {
        status(it.status, 200)
        val bytes = ByteArrayOutputStream(); copy(it, bytes, DeveloperUpdateRelease.MAX_METADATA)
        DeveloperUpdateRelease.parse(bytes.toByteArray())
    }
    override fun latest(): DeveloperUpdateRelease.Release = guarded { begin(); readRelease() }
    override fun download(release: DeveloperUpdateRelease.Release): File = guarded {
        begin(); DeveloperUpdateRelease.validate(release)
        val parent = cache.canonicalFile
        require(parent.isDirectory && !Files.isSymbolicLink(parent.toPath()))
        val folder = File(parent, "updates")
        require(!Files.isSymbolicLink(folder.toPath()) && folder.canonicalFile.parentFile == parent && (folder.isDirectory || folder.mkdir()))
        val file = File.createTempFile("developer-update-", ".apk", folder)
        try {
            request(DeveloperUpdateRelease.APK).use { first ->
                status(first.status, 200, 302)
                if (first.status == 200) file.outputStream().use { require(copy(first, it, release.bytes) == release.bytes) }
                else {
                    val location = first.location ?: throw DeveloperUpdateRelease.Failure(DeveloperUpdateRelease.Problem.METADATA)
                    try { requireStorage(location) } catch (_: Exception) {
                        throw DeveloperUpdateRelease.Failure(DeveloperUpdateRelease.Problem.METADATA)
                    }
                    request(location).use { payload ->
                        status(payload.status, 200)
                        file.outputStream().use { require(copy(payload, it, release.bytes) == release.bytes) }
                    }
                }
            }
            verifyBytes(file, release); inspect(file, release); check()
            if (readRelease() != release) throw DeveloperUpdateRelease.Failure(DeveloperUpdateRelease.Problem.CHANGED)
            check(); file
        } catch (error: Exception) { file.delete(); throw error }
    }
    override fun verify(file: File, release: DeveloperUpdateRelease.Release) = guarded {
        begin(); DeveloperUpdateRelease.validate(release)
        if (readRelease() != release) throw DeveloperUpdateRelease.Failure(DeveloperUpdateRelease.Problem.CHANGED)
        verifyBytes(file, release); inspect(file, release); check()
    }
    private fun verifyBytes(file: File, release: DeveloperUpdateRelease.Release) {
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.length() != release.bytes ||
            DevelopmentArtifactFiles.sha256(file) { cancellation.isCancelled } != release.sha256)
            throw DeveloperUpdateRelease.Failure(DeveloperUpdateRelease.Problem.STORAGE)
    }
    private fun <T> guarded(operation: () -> T): T = try { operation() }
        catch (e: DeveloperUpdateRelease.Failure) { throw e }
        catch (e: DevelopmentArtifact.Failure) { throw e }
        catch (_: Exception) {
            check()
            throw DeveloperUpdateRelease.Failure(DeveloperUpdateRelease.Problem.NETWORK)
        }
    companion object {
        internal fun requireStorage(url: String) {
            require(url.length <= 12_000)
            val uri = URI(url)
            require(uri.scheme == "https" && uri.host == "release-assets.githubusercontent.com" && uri.port == -1 &&
                uri.rawUserInfo == null && uri.rawFragment == null && !uri.rawQuery.isNullOrEmpty() &&
                uri.rawPath.matches(Regex("/github-production-release-asset/1410602023/[a-f0-9-]{36}")))
        }
        private fun requireUrl(url: String) {
            if (url != DeveloperUpdateRelease.API && url != DeveloperUpdateRelease.APK) requireStorage(url)
        }
        private fun http(cancellation: PatchPreviewCancellation) = BuildFeedback.Transport { url, token, timeout ->
            require(token == null); requireUrl(url)
            require(timeout in 1..DeveloperUpdateRelease.MAX_MILLIS)
            val connection = URL(url).openConnection() as HttpURLConnection
            val stop = cancellation.registerStop { connection.disconnect() }
            val timer = Timer("DeveloperUpdateTimeout", true)
            timer.schedule(object : TimerTask() { override fun run() { runCatching { connection.disconnect() } } }, timeout)
            try {
                cancellation.check()
                connection.requestMethod = "GET"; connection.instanceFollowRedirects = false
                connection.useCaches = false; connection.doOutput = false
                connection.connectTimeout = minOf(15_000L, timeout).toInt()
                connection.readTimeout = minOf(30_000L, timeout).toInt()
                connection.setRequestProperty("User-Agent", "JarvisDeveloper")
                if (url == DeveloperUpdateRelease.API) connection.setRequestProperty("Accept", "application/vnd.github+json")
                val status = connection.responseCode
                BuildFeedback.Response(status, connection.contentLengthLong, connection.url.toString(),
                    if (status in 200..299) connection.inputStream else ByteArrayInputStream(byteArrayOf()),
                    if (status == 302) connection.getHeaderField("Location") else null) {
                    stop.close(); timer.cancel(); connection.disconnect()
                }
            } catch (e: Exception) { stop.close(); timer.cancel(); connection.disconnect(); throw e }
        }
    }
}
