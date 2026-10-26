package com.jarvis.developer

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Exact-source GET-only artifact reader. Credentials never follow a storage redirect. */
internal class DevelopmentArtifactDownload(
    private val transport: BuildFeedback.Transport,
    private val files: DevelopmentArtifactFiles,
    private val inspect: (File, DevelopmentArtifact.Record) -> Unit,
    private val cancellation: PatchPreviewCancellation = PatchPreviewCancellation(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val api = "https://api.github.com/repos/muddigger782012/J.A.R.V.I.S.-"
    private val repository = "muddigger782012/J.A.R.V.I.S.-"
    private var started = 0L
    private var requests = 0
    private fun check() {
        if (cancellation.isCancelled) throw DevelopmentArtifact.Failure(DevelopmentArtifact.Problem.CANCELLED)
        if (clock() - started !in 0 until BuildFeedback.MAX_MILLIS || requests > 6)
            throw DevelopmentArtifact.Failure(DevelopmentArtifact.Problem.LIMIT)
    }
    private fun request(url: String, token: String?): BuildFeedback.Response {
        check(); require(++requests <= 6)
        if (token == null) BuildFeedback.requireStorageUrl(url) else BuildFeedback.requireApiUrl(url)
        return transport.get(url, token, BuildFeedback.MAX_MILLIS - (clock() - started)).also {
            it.watch(cancellation)
            if (it.finalUrl != url) { it.close(); throw IllegalArgumentException("Unexpected redirect") }
        }
    }
    private fun copy(response: BuildFeedback.Response, output: OutputStream, limit: Long): Long {
        require(response.contentLength <= limit)
        var count = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            check(); val n = response.body.read(buffer); if (n < 0) break
            count += n
            if (count > limit) throw DevelopmentArtifact.Failure(DevelopmentArtifact.Problem.LIMIT)
            output.write(buffer, 0, n)
        }
        require(response.contentLength < 0 || response.contentLength == count)
        return count
    }
    private fun json(url: String, token: String): JSONObject = request(url, token).use { response ->
        requireStatus(response.status, 200)
        val output = java.io.ByteArrayOutputStream()
        copy(response, output, 256 * 1024)
        boundedJson(output.toByteArray())
    }
    private fun requireStatus(status: Int, vararg allowed: Int) {
        if (status !in allowed) throw DevelopmentArtifact.Failure(if (status in setOf(401, 403))
            DevelopmentArtifact.Problem.ACCESS else DevelopmentArtifact.Problem.NETWORK)
    }
    fun read(source: DevelopmentArtifact.Source, runId: Long, token: String, supportedAbis: List<String>): DevelopmentArtifact.Record {
        DevelopmentArtifact.validateSource(source)
        require(runId > 0 && token.length <= 4096 && token.none { it.isISOControl() })
        started = clock(); requests = 0
        val archive = files.temporary()
        try {
            val run = json("$api/actions/runs/$runId", token)
            require(run.getLong("id") == runId && run.getString("head_sha") == source.commit &&
                run.getString("head_branch") == source.branch && run.getString("status") == "completed" &&
                run.getString("conclusion") == "success" && run.getString("event") in setOf("push", "workflow_dispatch"))
            require(run.getJSONObject("head_repository").getString("full_name") == repository &&
                run.getJSONObject("repository").getString("full_name") == repository)
            val workflow = run.getString("path")
            val artifactName = when {
                source.target == PatchEngine.Target.DEVELOPER && workflow == ".github/workflows/build-jarvis-developer.yml" -> "Jarvis-Developer-debug-APK"
                source.target == PatchEngine.Target.DEVELOPER && workflow == ".github/workflows/release-jarvis-developer.yml" -> "Jarvis-Developer-signed-release-candidate"
                source.target == PatchEngine.Target.PERSONAL && workflow == ".github/workflows/build-jarvis.yml" -> "Jarvis-personal-job-APK"
                else -> error("Unsupported build workflow")
            }
            val attempt = run.getInt("run_attempt"); require(attempt in 1..1_000_000)
            val list = json("$api/actions/runs/$runId/artifacts?per_page=30", token)
            val rows = list.getJSONArray("artifacts")
            require(list.getInt("total_count") == rows.length() && rows.length() <= 30)
            val chosen = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.getString("name") == artifactName }
            require(chosen.size == 1)
            val artifact = chosen.single()
            val id = artifact.getLong("id"); require(id > 0 && !artifact.getBoolean("expired"))
            require(artifact.getJSONObject("workflow_run").getLong("id") == runId &&
                artifact.getJSONObject("workflow_run").getString("head_sha") == source.commit)
            val digest = artifact.getString("digest").also { require(it.matches(Regex("sha256:[a-f0-9]{64}"))) }.removePrefix("sha256:")
            val size = artifact.getLong("size_in_bytes"); require(size in 1..DevelopmentArtifact.MAX_ARCHIVE)
            request("$api/actions/artifacts/$id/zip", token).use { first ->
                requireStatus(first.status, 200, 302)
                if (first.status == 200) archive.outputStream().use { require(copy(first, it, size) == size) }
                else {
                    require(first.status == 302)
                    val location = requireNotNull(first.location); BuildFeedback.requireStorageUrl(location)
                    request(location, null).use { data ->
                        requireStatus(data.status, 200)
                        archive.outputStream().use { require(copy(data, it, size) == size) }
                    }
                }
            }
            require(DevelopmentArtifactFiles.sha256(archive) == digest)
            return unpack(archive, source, runId, attempt, id, digest, workflow, supportedAbis)
        } catch (failure: DevelopmentArtifact.Failure) { throw failure }
        catch (_: IOException) {
            check(); throw DevelopmentArtifact.Failure(DevelopmentArtifact.Problem.NETWORK)
        } catch (_: Exception) {
            check(); throw DevelopmentArtifact.Failure(DevelopmentArtifact.Problem.PROVENANCE)
        } finally { archive.delete() }
    }
    private fun unpack(archive: File, source: DevelopmentArtifact.Source, run: Long, attempt: Int,
        id: Long, digest: String, workflow: String, abis: List<String>): DevelopmentArtifact.Record = ZipFile(archive).use { zip ->
        check()
        val entries = zip.entries().asSequence().toList()
        require(entries.size in 2..4 && entries.map { it.name }.distinct().size == entries.size && entries.none { it.isDirectory })
        val manifestEntry = requireNotNull(zip.getEntry("development-artifact.json"))
        require(manifestEntry.size in 1..16_384)
        val bytes = zip.getInputStream(manifestEntry).use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                require(output.size() + n <= 16_384); output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
        require(bytes.size.toLong() == manifestEntry.size)
        val manifest = boundedJson(bytes)
        require(DevelopmentArtifact.number(manifest, "schemaVersion") == 1L && manifest.getString("sourceCommit") == source.commit &&
            DevelopmentArtifact.number(manifest, "runId") == run && DevelopmentArtifact.number(manifest, "runAttempt") == attempt.toLong() && manifest.getString("target") == source.target.name)
        val signing = manifest.getString("signing")
        require(signing == if (workflow.endsWith("/build-jarvis-developer.yml")) "debug" else "production")
        val rows = manifest.getJSONArray("apks"); require(rows.length() in 1..3)
        val apks = (0 until rows.length()).map { rows.getJSONObject(it) }
        val names = apks.map { it.getString("file").also { name -> require(name.matches(Regex("[A-Za-z0-9._-]{1,120}\\.apk"))) } }
        require(names.distinct().size == names.size && entries.map { it.name }.toSet() == (names + "development-artifact.json").toSet())
        fun native(row: JSONObject): List<String> = row.getJSONArray("abis").let { a ->
            require(a.length() <= 4); (0 until a.length()).map { a.getString(it) }
        }
        val candidate = abis.firstNotNullOfOrNull { abi -> apks.filter { abi in native(it) }.singleOrNull() }
            ?: apks.filter { native(it).isEmpty() }.singleOrNull()
            ?: throw DevelopmentArtifact.Failure(DevelopmentArtifact.Problem.COMPATIBILITY)
        val record = DevelopmentArtifact.Record(source.commit, run, attempt, id, digest, candidate.getString("sha256"),
            DevelopmentArtifact.number(candidate, "bytes"), candidate.getString("packageName"), DevelopmentArtifact.number(candidate, "versionCode"),
            candidate.getString("versionName"), candidate.getString("signerSha256"), signing)
        DevelopmentArtifact.validate(record)
        require(record.packageName == DevelopmentArtifact.packageName(source.target))
        val entry = requireNotNull(zip.getEntry(candidate.getString("file"))); require(entry.size == record.bytes)
        val temporary = files.temporaryApk()
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            zip.getInputStream(entry).use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024); var total = 0L
                while (true) {
                    check(); val n = input.read(buffer); if (n < 0) break
                    total += n; require(total <= record.bytes)
                    hash.update(buffer, 0, n); output.write(buffer, 0, n)
                }
                require(total == record.bytes)
            } }
            require(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == record.apkSha256)
            inspect(temporary, record); check()
            val destination = files.apk(record.apkSha256)
            if (destination.exists()) files.checked(record) else require(temporary.renameTo(destination))
            record
        } finally { temporary.delete() }
    }
    private fun boundedJson(bytes: ByteArray): JSONObject {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        var depth = 0; var quoted = false; var escaped = false
        for (c in text) {
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) { '"' -> quoted = true; '{', '[' -> { require(++depth <= 12) }; '}', ']' -> require(--depth >= 0) }
        }
        require(!quoted && depth == 0)
        return JSONObject(text)
    }
}
