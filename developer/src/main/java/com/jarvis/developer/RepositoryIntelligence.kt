package com.jarvis.developer

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64

/** Repository analysis has a GET-only transport and never invokes the coding/provider layer. */
object RepositoryIntelligence {
    private const val REPOSITORY = "muddigger782012/J.A.R.V.I.S.-"
    private const val BRANCH = "feature/jarvis-developer"
    private const val API = "https://api.github.com/repos/$REPOSITORY"
    internal const val MAX_SOURCE_BYTES = 200_000
    internal const val MAX_TREE_BYTES = 2_000_000
    internal const val MAX_SESSION_REQUESTS = 64
    internal const val MAX_SESSION_MILLIS = 180_000L
    private const val MAX_FILES = 2500
    private const val MAX_NODES = 10_000
    private val shaPattern = Regex("[a-fA-F0-9]{40}")

    /** The only network operation available to the analysis engine. */
    internal fun interface GetTransport {
        fun get(url: String, token: String): GetResponse
    }

    internal class GetResponse(
        val status: Int,
        val contentLength: Long,
        val finalUrl: String,
        val body: InputStream,
        private val disconnect: () -> Unit = {}
    ) : Closeable {
        override fun close() {
            try { body.close() } finally { disconnect() }
        }
    }

    private fun http(cancellation: PatchPreviewCancellation? = null) = GetTransport { url, token ->
        requireApiUrl(url, null) // Reader validates the explicit product before using this shared transport.
        val connection = URL(url).openConnection() as HttpURLConnection
        val stop = cancellation?.registerStop { connection.disconnect() }
        try {
            cancellation?.check()
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.doOutput = false
            connection.useCaches = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "JarvisDeveloper-ReadOnly")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
            val status = connection.responseCode
            // Do not read server error bodies, which can reflect credentials or unrelated content.
            val stream = if (status in 200..299) connection.inputStream else ByteArrayInputStream(byteArrayOf())
            GetResponse(status, connection.contentLengthLong, connection.url.toString(), stream) {
                stop?.close()
                connection.disconnect()
            }
        } catch (_: Exception) {
            stop?.close()
            connection.disconnect()
            // Network exceptions may contain request URLs; provide a stable, non-sensitive error.
            throw IllegalStateException("Repository GET failed")
        }
    }

    fun inventory(token: String, branch: String = BRANCH, target: PatchEngine.Target = PatchEngine.Target.DEVELOPER): RepositoryAnalysis.Inventory = Reader(http()).inventory(token, branch, target)

    internal fun inventory(token: String, branch: String, target: PatchEngine.Target,
        cancellation: PatchPreviewCancellation): RepositoryAnalysis.Inventory =
        Reader(http(cancellation), cancellation).inventory(token, branch, target)

    /** Explicit GET-only Personal review; the caller still needs a separate complete-diff approval. */
    fun personalRepair(token: String, draft: PersonalRepair.Draft, branch: String, newTests: List<String> = emptyList()): PersonalRepair.Review {
        val reader = Reader(http())
        val inventory = reader.inventory(token, branch, PatchEngine.Target.PERSONAL)
        return PersonalRepair.prepare(draft, branch, inventory, reader.readSource(token, inventory, draft.path), newTests)
    }

    fun personalFollowUp(token: String, result: PatchPublisher.CommitResult, report: BuildDiagnostics.Report,
        path: String): PersonalRepair.Review = Reader(http()).personalFollowUp(token, result, report, path)

    fun readSource(token: String, inventory: RepositoryAnalysis.Inventory, path: String): String =
        Reader(http()).readSource(token, inventory, path)

    internal fun readSource(token: String, inventory: RepositoryAnalysis.Inventory, path: String,
        cancellation: PatchPreviewCancellation): String = Reader(http(cancellation), cancellation).readSource(token, inventory, path)

    fun analyze(token: String, task: String, branch: String = BRANCH): RepositoryAnalysis.AnalysisPlan = analyzeSnapshot(token, task, branch).second

    fun analyzeSnapshot(token: String, task: String, branch: String = BRANCH): Pair<RepositoryAnalysis.Inventory, RepositoryAnalysis.AnalysisPlan> =
        Reader(http()).analyzeSnapshot(token, task, branch)

    /** Injectable for deterministic JVM tests; it exposes neither write methods nor arbitrary hosts. */
    internal class Reader(
        private val transport: GetTransport,
        private val cancellation: PatchPreviewCancellation? = null,
        private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }
    ) {
        private val startedAt = nowMillis()
        private var requestCount = 0
        private var stoppedReason: String? = null
        private var selectedTarget = PatchEngine.Target.DEVELOPER

        fun personalFollowUp(token: String, result: PatchPublisher.CommitResult, report: BuildDiagnostics.Report,
            path: String): PersonalRepair.Review {
            require(path in PersonalRepair.followUpPaths(result, report))
            val inventory = inventory(token, result.branch, PatchEngine.Target.PERSONAL)
            require(inventory.commitSha == result.commitSha) { "Failed source branch moved; read current CI again" }
            return PersonalRepair.prepareFollowUp(result, report, path, inventory, readSource(token, inventory, path))
        }

        fun inventory(token: String, branch: String = BRANCH, target: PatchEngine.Target = PatchEngine.Target.DEVELOPER): RepositoryAnalysis.Inventory {
            require(PatchEngine.validSourceBranch(branch, target)) { "Repository branch is outside selected product scope" }
            selectedTarget = target
            val encodedBranch = URLEncoder.encode(branch, "UTF-8")
            val ref = getJson("$API/git/ref/heads/$encodedBranch", token, 64_000)
            require(ref.getString("ref") == "refs/heads/$branch") { "Unexpected repository reference" }
            val refObject = ref.getJSONObject("object")
            require(refObject.getString("type") == "commit") { "Repository reference is not a commit" }
            val commitSha = validSha(refObject.getString("sha"))
            // Resolve the immutable commit's tree, rather than trusting a moving branch/tree alias.
            val commit = getJson("$API/git/commits/$commitSha", token, 64_000)
            require(validSha(commit.getString("sha")) == commitSha) { "Commit identity mismatch" }
            val treeSha = validSha(commit.getJSONObject("tree").getString("sha"))
            val tree = getJson("$API/git/trees/$treeSha?recursive=1", token, MAX_TREE_BYTES)
            require(validSha(tree.getString("sha")) == treeSha) { "Tree identity mismatch" }
            require(tree.get("truncated") == false) { "GitHub returned a truncated tree; inventory is incomplete" }
            val nodes = tree.getJSONArray("tree")
            require(nodes.length() <= MAX_NODES) { "Repository tree exceeds safety limit" }
            val files = ArrayList<RepositoryAnalysis.SourceFile>()
            val seenPaths = HashSet<String>()
            for (index in 0 until nodes.length()) {
                val node = nodes.getJSONObject(index)
                val path = node.getString("path")
                require(validPath(path) && seenPaths.add(path)) { "Invalid or duplicate repository path" }
                val sha = validSha(node.getString("sha"))
                val type = node.getString("type")
                val mode = node.getString("mode")
                when (type) {
                    "tree" -> require(mode == "040000") { "Invalid repository tree mode" }
                    "commit" -> require(mode == "160000") { "Invalid submodule mode" }
                    "blob" -> {
                        require(mode in setOf("100644", "100755", "120000")) { "Invalid repository blob mode" }
                        val size = nonnegativeSize(node)
                        // Symlinks, large files and unapproved scopes are never offered for browsing.
                        if (mode != "120000" && size <= MAX_SOURCE_BYTES && allowedSource(path)) {
                            files.add(RepositoryAnalysis.SourceFile(path, sha, size))
                            require(files.size <= MAX_FILES) { "Source inventory exceeds safety limit" }
                        }
                    }
                    else -> error("Invalid repository tree entry type")
                }
            }
            return RepositoryAnalysis.Inventory(files.sortedBy { it.path }, false, commitSha)
        }

        fun readSource(token: String, inventory: RepositoryAnalysis.Inventory, path: String): String {
            val commitSha = validSha(inventory.commitSha)
            require(!inventory.truncated) { "Cannot read from a truncated inventory" }
            require(allowedSource(path)) { "Path is outside approved source scopes" }
            val matches = inventory.files.filter { it.path == path }
            require(matches.size == 1) { "File is not uniquely present in the pinned inventory" }
            val file = matches.single()
            val blobSha = validSha(file.sha)
            require(file.size in 0..MAX_SOURCE_BYTES.toLong()) { "File exceeds read limit" }
            val encodedPath = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8") }
            val json = getJson("$API/contents/$encodedPath?ref=$commitSha", token, 350_000)
            require(json.getString("type") == "file" && json.getString("path") == path) { "Unexpected source response" }
            require(validSha(json.getString("sha")) == blobSha) { "Source blob identity mismatch" }
            require(nonnegativeSize(json) == file.size) { "Source size differs from inventory" }
            require(json.getString("encoding") == "base64") { "Unsupported source encoding" }
            val content = json.getString("content")
            require(content.length <= 280_000) { "Encoded source exceeds read limit" }
            // GitHub wraps base64 with newlines. Do not use MIME decoding, which ignores arbitrary garbage.
            val compact = content.replace("\n", "").replace("\r", "")
            val expectedEncodedSize = ((file.size + 2) / 3 * 4).toInt()
            require(compact.length == expectedEncodedSize && compact.matches(Regex("[A-Za-z0-9+/]*={0,2}"))) {
                "Invalid bounded source encoding"
            }
            val decoded = Base64.getDecoder().decode(compact)
            require(decoded.size.toLong() == file.size && decoded.size <= MAX_SOURCE_BYTES) { "Source exceeds read limit" }
            require(decoded.none { it == 0.toByte() }) { "Binary source content is not permitted" }
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update("blob ${decoded.size}\u0000".toByteArray(Charsets.UTF_8))
            val actualSha = digest.digest(decoded).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            require(actualSha == blobSha) { "Source bytes do not match the pinned Git blob" }
            return strictUtf8(decoded)
        }

        fun analyze(token: String, task: String, branch: String = BRANCH): RepositoryAnalysis.AnalysisPlan {
            return analyzeSnapshot(token, task, branch).second
        }

        fun analyzeSnapshot(token: String, task: String, branch: String = BRANCH): Pair<RepositoryAnalysis.Inventory, RepositoryAnalysis.AnalysisPlan> {
            require(task.isNotBlank() && task.length <= 4_000) { "Task must contain 1–4,000 characters" }
            val inventory = inventory(token, branch)
            val plan = RepositoryAnalysis.analyze(task, inventory) { path -> readSource(token, inventory, path) }
            val reason = stoppedReason
            return inventory to if (reason == null) plan else plan.copy(limitations = plan.limitations + reason)
        }

        private fun stop(reason: String): Nothing {
            stoppedReason = reason
            error(reason)
        }

        private fun checkTimeBudget() {
            cancellation?.check()
            stoppedReason?.let { error(it) }
            if (nowMillis() - startedAt >= MAX_SESSION_MILLIS) {
                stop("Repository reads stopped at the 3-minute session budget; retrieved context is incomplete.")
            }
        }

        private fun getJson(url: String, token: String, limit: Int): JSONObject {
            requireApiUrl(url, selectedTarget)
            checkTimeBudget()
            if (requestCount >= MAX_SESSION_REQUESTS) {
                stop("Repository reads stopped at the 64-request session limit; retrieved context is incomplete.")
            }
            requestCount++
            return transport.get(url, token).use { response ->
                cancellation?.registerStop { response.body.close() }.use {
                    checkTimeBudget()
                    requireApiUrl(response.finalUrl, selectedTarget)
                    require(response.finalUrl == url) { "Repository redirects are not permitted" }
                    if (response.status in setOf(401, 403, 429)) {
                        stop("Repository reads stopped after HTTP ${response.status}; check read access or rate limits before retrying.")
                    }
                    require(response.status in 200..299) { "Repository read failed (HTTP ${response.status})" }
                    require(response.contentLength in -1..limit.toLong()) { "Repository response exceeds read limit" }
                    val output = ByteArrayOutputStream(minOf(8192, limit))
                    val buffer = ByteArray(8192)
                    var count = 0
                    while (true) {
                        // Cancellation closes active resources; the existing budgets still apply.
                        checkTimeBudget()
                        val read = response.body.read(buffer, 0, minOf(buffer.size, limit - count + 1))
                        checkTimeBudget()
                        if (read == -1) break
                        require(read > 0) { "Repository stream made no progress" }
                        count += read
                        require(count <= limit) { "Repository response exceeds read limit" }
                        output.write(buffer, 0, read)
                    }
                    JSONObject(strictUtf8(output.toByteArray()))
                }
            }
        }
    }

    internal fun requireApiUrl(value: String, target: PatchEngine.Target? = PatchEngine.Target.DEVELOPER) {
        val uri = URI(value)
        require(uri.scheme == "https" && uri.host == "api.github.com" && uri.port == -1 &&
            uri.rawUserInfo == null && uri.rawFragment == null && value.startsWith(API + "/")) {
            "Repository reads must stay on the approved GitHub API origin"
        }
        val endpoint = value.removePrefix(API)
        val refPrefix = "/git/ref/heads/"
        val ref = if (endpoint.startsWith(refPrefix)) {
            val encoded = endpoint.removePrefix(refPrefix)
            try {
                val branch = java.net.URLDecoder.decode(encoded, "UTF-8")
                (if (target == null) PatchEngine.Target.entries.any { PatchEngine.validSourceBranch(branch, it) }
                    else PatchEngine.validSourceBranch(branch, target)) && URLEncoder.encode(branch, "UTF-8") == encoded
            } catch (_: Exception) { false }
        } else false
        val source = if (endpoint.startsWith("/contents/")) {
            val path = endpoint.removePrefix("/contents/").substringBefore("?ref=")
            allowedSource(path) && endpoint == "/contents/$path?ref=${endpoint.substringAfter("?ref=", "")}" &&
                endpoint.substringAfter("?ref=", "").matches(Regex("[a-f0-9]{40}"))
        } else false
        require(ref || source || endpoint.matches(Regex("/git/commits/[a-f0-9]{40}")) ||
            endpoint.matches(Regex("/git/trees/[a-f0-9]{40}\\?recursive=1"))) {
            "Repository operation is outside scoped read-only analysis"
        }
    }

    private fun validSha(value: String): String {
        require(shaPattern.matches(value)) { "Invalid immutable Git identity" }
        return value.lowercase()
    }

    private fun validPath(path: String): Boolean = path.length in 1..512 &&
        path.matches(Regex("[A-Za-z0-9_./-]+")) &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    internal fun allowedSource(path: String): Boolean {
        if (!validPath(path) || path.split('/').any { it.startsWith('.') }) return false
        val protectedNames = listOf("secret", "credential", "keystore", "signing")
        if (path.split('/').dropLast(1).any { component ->
                protectedNames.any { component.lowercase().contains(it) }
            }) return false
        val file = path.substringAfterLast('/').lowercase()
        if (file.contains("secret") || file.contains("credential") || file.contains("keystore") ||
            file.contains("signing") || file == "google-services.json" || file == "local.properties") return false
        if (path in setOf("AGENTS.md", "README.md", "developer/AGENTS.md", "developer/DEVELOPMENT_ROADMAP.md",
                "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
                "app/build.gradle", "app/build.gradle.kts", "developer/build.gradle", "developer/build.gradle.kts",
                "gradle/libs.versions.toml", "gradle/wrapper/gradle-wrapper.properties")) return true
        if (path.startsWith("docs/") && path.endsWith(".md")) return true
        if (path in setOf("app/src/main/AndroidManifest.xml", "developer/src/main/AndroidManifest.xml")) return true
        if (Regex("(?:developer|app)/src/(?:main|test|androidTest)/(?:java|kotlin)/(?:[A-Za-z0-9_/-]+)\\.(?:kt|java)").matches(path)) {
            return path.startsWith("developer/src/") || Regex("app/src/(?:main|test|androidTest)/(?:java|kotlin)/com/assistant/core/.*").matches(path)
        }
        return Regex("(?:developer|app)/src/main/res/(?:layout|menu|drawable|values|xml)(?:-[A-Za-z0-9_-]+)?/[A-Za-z0-9_-]+\\.xml").matches(path)
    }

    private fun nonnegativeSize(json: JSONObject): Long {
        val value = json.get("size")
        require(value is Number) { "Missing source size" }
        val size = value.toString().toLongOrNull() ?: error("Invalid source size")
        require(size >= 0) { "Invalid source size" }
        return size
    }

    private fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
}
