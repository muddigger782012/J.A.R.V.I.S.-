package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.Timer
import java.util.TimerTask

/** Writes only a reviewed proposal to a new task branch. It cannot update a ref or publish a release. */
object PatchPublisher {
    private const val REPOSITORY = "muddigger782012/J.A.R.V.I.S.-"
    private const val API = "https://api.github.com/repos/$REPOSITORY"
    private const val BASE_BRANCH = "feature/jarvis-developer"
    private const val WORKFLOW = "build-jarvis-developer.yml"
    private const val MAX_RESPONSE_BYTES = 2_000_000
    private const val MAX_REQUEST_BYTES = 2_000_000
    private const val MAX_REQUESTS = 64
    private const val MAX_MILLIS = 180_000L
    private val shaPattern = Regex("[a-f0-9]{40}")
    private val pathPattern = Regex("[A-Za-z0-9._/-]{1,512}")

    data class CommitResult(
        val taskId: String,
        val provider: String,
        val model: String,
        val proposalId: String,
        val paths: List<String>,
        val baseCommitSha: String,
        val commitSha: String,
        val branch: String,
        val diff: String,
        val baseBranch: String = BASE_BRANCH
    )

    enum class CiState { MISSING, QUEUED, RUNNING, PASSED, FAILED }
    data class CiStatus(val state: CiState, val runUrl: String? = null, val conclusion: String? = null)

    /** Error bodies and transport exceptions are deliberately not retained. */
    internal class ApiFailure(val status: Int) : IllegalStateException("GitHub request failed (HTTP $status)")

    /** Paths are relative to the single approved repository, never arbitrary URLs. */
    internal fun interface GithubApi {
        fun request(method: String, path: String, token: String, payload: JSONObject?): JSONObject
    }

    private val http = GithubApi { method, path, token, payload ->
        validateEndpoint(method, path)
        require(token.isNotBlank() && token.length <= 4096 && token.none { it.isISOControl() }) {
            "A valid in-memory GitHub token is required"
        }
        val body = payload?.toString()?.toByteArray(Charsets.UTF_8)
        require((method == "POST") == (body != null)) { "Unexpected GitHub request body" }
        require(body == null || body.size <= MAX_REQUEST_BYTES) { "GitHub request exceeds safety limit" }
        val connection = URL(API + path).openConnection() as HttpURLConnection
        val transportStarted = System.nanoTime()
        // Socket read/connect timeouts do not cover a blocked request-body upload.
        val deadline = Timer("JarvisDeveloper-GitHubTimeout", true)
        deadline.schedule(object : TimerTask() {
            override fun run() { try { connection.disconnect() } catch (_: Exception) { /* no background error logging */ } }
        }, MAX_MILLIS)
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "JarvisDeveloper-ScopedPatch")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw ApiFailure(status)
            require(connection.url.toString() == API + path) { "GitHub redirects are not permitted" }
            require(connection.contentLengthLong in -1..MAX_RESPONSE_BYTES.toLong()) { "GitHub response exceeds safety limit" }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(8192)
                var count = 0
                while (true) {
                    require((System.nanoTime() - transportStarted) / 1_000_000 < MAX_MILLIS) { "GitHub response exceeded time budget" }
                    val read = stream.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES - count + 1))
                    if (read == -1) break
                    require(read > 0) { "GitHub response made no progress" }
                    count += read
                    require(count <= MAX_RESPONSE_BYTES) { "GitHub response exceeds safety limit" }
                    output.write(buffer, 0, read)
                }
            }
            JSONObject(strictUtf8(output.toByteArray()))
        } catch (failure: ApiFailure) {
            throw failure
        } catch (_: Exception) {
            // Exceptions can include URLs, request contents or credentials; never expose them.
            throw IllegalStateException("GitHub transport or response validation failed")
        } finally {
            deadline.cancel()
            connection.disconnect()
        }
    }

    fun commit(token: String, approved: PatchEngine.ApprovedProposal): CommitResult = Publisher(http).commit(token, approved)
    fun ci(token: String, result: CommitResult): CiStatus = Publisher(http).ci(token, result)

    internal class Publisher(
        private val api: GithubApi,
        private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }
    ) {
        private val started = nowMillis()
        private var requests = 0

        fun commit(token: String, approved: PatchEngine.ApprovedProposal): CommitResult {
            PatchEngine.validateApproval(approved)
            val proposal = approved.proposal
            val request = proposal.request
            val branch = PatchEngine.branchName(proposal)
            var stage = "base revision check"
            var visible = false
            var writesStarted = false
            var branchAttempted = false
            try {
                require(head(token, request.baseBranch) == request.baseCommitSha) { "Base branch moved; regenerate and review the proposal" }
                val commit = call("GET", "/git/commits/${request.baseCommitSha}", token)
                require(identity(commit) == request.baseCommitSha) { "Base commit identity mismatch" }
                val baseTree = sha(commit.getJSONObject("tree").getString("sha"))
                stage = "source preimage validation"
                val before = tree(token, baseTree)
                validatePreimages(proposal, before)
                // An existing task branch must never be updated, even if it points to the same commit.
                stage = "task branch collision check"
                val exists = try { head(token, branch); true } catch (failure: ApiFailure) {
                    if (failure.status == 404) false else throw failure
                }
                require(!exists) { "Task branch already exists; no branch was changed" }
                val entries = JSONArray()
                val expected = before.toMutableMap()
                stage = "unreferenced Git objects"
                for (edit in proposal.edits) {
                    val bytes = edit.content.toByteArray(Charsets.UTF_8)
                    val expectedSha = blobSha(bytes)
                    writesStarted = true
                    val blob = write(token, approved, "/git/blobs", JSONObject()
                        .put("content", Base64.getEncoder().encodeToString(bytes)).put("encoding", "base64"))
                    require(identity(blob) == expectedSha) { "Created blob identity mismatch" }
                    val mode = before[edit.path]?.mode ?: "100644"
                    expected[edit.path] = Node(edit.path, "blob", mode, expectedSha)
                    var parent = edit.path.substringBeforeLast('/', "")
                    while (parent.isNotEmpty()) {
                        if (!expected.containsKey(parent)) expected[parent] = Node(parent, "tree", "040000", "")
                        parent = parent.substringBeforeLast('/', "")
                    }
                    entries.put(JSONObject().put("path", edit.path).put("type", "blob").put("mode", mode).put("sha", expectedSha))
                }
                val createdTree = write(token, approved, "/git/trees", JSONObject().put("base_tree", baseTree).put("tree", entries))
                val treeSha = identity(createdTree)
                require(treeSha != baseTree) { "Proposal did not create a changed tree" }
                val actual = tree(token, treeSha)
                require(actual.keys == expected.keys && actual.all { (path, node) ->
                    val wanted = expected.getValue(path)
                    node.type == wanted.type && node.mode == wanted.mode && (node.type == "tree" || node.sha == wanted.sha)
                }) { "Created tree differs from the approved file changes" }
                val message = "Jarvis Developer: scoped task ${request.taskId}\n\n" +
                    "Proposal: ${proposal.id}\nBase: ${request.baseCommitSha}\nSource branch: ${request.baseBranch}\n" +
                    "Provider: ${auditLabel(request.provider)}\nModel: ${auditLabel(request.model)}"
                val createdCommit = write(token, approved, "/git/commits", JSONObject().put("message", message)
                    .put("tree", treeSha).put("parents", JSONArray().put(request.baseCommitSha)))
                val commitSha = identity(createdCommit)
                validateCommit(createdCommit, commitSha, treeSha, request.baseCommitSha)
                validateCommit(call("GET", "/git/commits/$commitSha", token), commitSha, treeSha, request.baseCommitSha)
                require(head(token, request.baseBranch) == request.baseCommitSha) { "Base branch moved; task branch was not created" }
                stage = "new branch creation"
                branchAttempted = true
                val ref = write(token, approved, "/git/refs", JSONObject().put("ref", "refs/heads/$branch").put("sha", commitSha))
                visible = true
                validateRef(ref, branch, commitSha)
                require(head(token, branch) == commitSha) { "Task branch readback failed" }
                return CommitResult(request.taskId, request.provider, request.model, proposal.id,
                    proposal.edits.map { it.path }.sorted(), request.baseCommitSha, commitSha, branch, proposal.diff, request.baseBranch)
            } catch (failure: Exception) {
                // Object creation can leave unreachable objects; a failed ref request can have an unknown outcome.
                // Never retry or overwrite a ref automatically.
                val outcome = when {
                    visible -> "A new task branch may exist; verify it manually before retrying."
                    branchAttempted -> "Branch creation outcome is unknown; check the task branch before retrying."
                    writesStarted -> "Unreferenced Git objects may exist; no task branch was created."
                    else -> "No Git write was attempted."
                }
                val status = if (failure is ApiFailure) " HTTP ${failure.status}." else ""
                throw IllegalStateException("Scoped commit stopped during $stage.$status $outcome")
            }
        }

        fun ci(token: String, result: CommitResult): CiStatus {
            sha(result.baseCommitSha)
            sha(result.commitSha)
            require(BuildDiagnostics.validBranch(result.baseBranch)) { "Invalid source branch" }
            require(result.taskId.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,47}")) &&
                result.proposalId.matches(Regex("[a-f0-9]{64}")) &&
                result.branch == "feature/jarvis-developer-task/${result.taskId}-${result.proposalId.take(12)}") { "Invalid task build identity" }
            val path = "/actions/workflows/$WORKFLOW/runs?branch=${encode(result.branch)}&head_sha=${result.commitSha}&per_page=20"
            val runs = call("GET", path, token).getJSONArray("workflow_runs")
            require(runs.length() <= 20) { "CI response exceeds requested limit" }
            val matches = (0 until runs.length()).map { runs.getJSONObject(it) }.filter {
                it.optString("head_sha") == result.commitSha && it.optString("head_branch") == result.branch &&
                    it.optString("path").substringBefore('@') == ".github/workflows/$WORKFLOW"
            }
            val run = matches.maxWithOrNull(compareBy<JSONObject> { it.optLong("run_number", -1) }.thenBy { it.optInt("run_attempt", 1) })
                ?: return CiStatus(CiState.MISSING)
            val id = run.getLong("id")
            require(id > 0) { "Invalid CI run identity" }
            val url = "https://github.com/$REPOSITORY/actions/runs/$id"
            return when (run.getString("status")) {
                "queued", "requested", "waiting", "pending" -> CiStatus(CiState.QUEUED, url)
                "in_progress" -> CiStatus(CiState.RUNNING, url)
                "completed" -> {
                    val conclusion = run.optString("conclusion")
                    require(conclusion in setOf("success", "failure", "neutral", "cancelled", "skipped", "timed_out", "action_required", "stale", "startup_failure")) {
                        "Unknown CI conclusion"
                    }
                    CiStatus(if (conclusion == "success") CiState.PASSED else CiState.FAILED, url, conclusion)
                }
                else -> error("Unknown CI state")
            }
        }

        private fun call(method: String, path: String, token: String, payload: JSONObject? = null): JSONObject {
            validateEndpoint(method, path)
            require(requests < MAX_REQUESTS && nowMillis() - started < MAX_MILLIS) { "GitHub session safety budget exceeded" }
            require(payload == null || payload.toString().toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) { "GitHub request exceeds safety limit" }
            requests++
            val response = api.request(method, path, token, payload)
            require(nowMillis() - started < MAX_MILLIS) { "GitHub session safety budget exceeded" }
            require(response.toString().toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES) { "GitHub response exceeds safety limit" }
            return response
        }

        private fun write(token: String, approved: PatchEngine.ApprovedProposal, path: String, payload: JSONObject): JSONObject {
            PatchEngine.validateApproval(approved)
            return call("POST", path, token, payload)
        }

        private fun head(token: String, branch: String): String {
            val ref = call("GET", "/git/ref/heads/${encode(branch)}", token)
            val value = sha(ref.getJSONObject("object").getString("sha"))
            validateRef(ref, branch, value)
            return value
        }

        private fun tree(token: String, treeSha: String): Map<String, Node> {
            val response = call("GET", "/git/trees/$treeSha?recursive=1", token)
            require(identity(response) == treeSha && response.get("truncated") == false) { "Incomplete or mismatched Git tree" }
            val entries = response.getJSONArray("tree")
            require(entries.length() <= 10_000) { "Git tree exceeds safety limit" }
            val nodes = LinkedHashMap<String, Node>()
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                val path = entry.getString("path")
                require(validTreePath(path) && !nodes.containsKey(path)) { "Invalid or duplicate tree path" }
                val type = entry.getString("type")
                val mode = entry.getString("mode")
                require((type == "tree" && mode == "040000") || (type == "blob" && mode in setOf("100644", "100755", "120000")) ||
                    (type == "commit" && mode == "160000")) { "Invalid tree entry mode" }
                nodes[path] = Node(path, type, mode, sha(entry.getString("sha")))
            }
            // Git trees must contain every ancestor as a real directory.
            nodes.keys.forEach { path ->
                var parent = path.substringBeforeLast('/', "")
                while (parent.isNotEmpty()) {
                    require(nodes[parent]?.type == "tree") { "Git tree has a non-directory ancestor" }
                    parent = parent.substringBeforeLast('/', "")
                }
            }
            return nodes
        }

        private fun validatePreimages(proposal: PatchEngine.Proposal, before: Map<String, Node>) {
            val sources = proposal.request.sources.associateBy { it.path }
            for (edit in proposal.edits) {
                require(PatchEngine.allowedPath(edit.path)) { "Path is outside approved write scopes" }
                val source = sources.getValue(edit.path)
                val old = before[edit.path]
                if (source.sha == null) {
                    require(old == null && before.keys.none { it.startsWith(edit.path + "/") }) { "New file path already exists" }
                } else {
                    require(old?.type == "blob" && old.mode in setOf("100644", "100755") && old.sha == source.sha) { "Source preimage or file mode changed" }
                }
                var parent = edit.path.substringBeforeLast('/', "")
                while (parent.isNotEmpty()) {
                    require(before[parent] == null || before[parent]?.type == "tree") { "File ancestor is not a directory" }
                    parent = parent.substringBeforeLast('/', "")
                }
            }
        }
    }

    private data class Node(val path: String, val type: String, val mode: String, val sha: String)

    private fun validateCommit(value: JSONObject, commitSha: String, treeSha: String, parentSha: String) {
        require(identity(value) == commitSha && sha(value.getJSONObject("tree").getString("sha")) == treeSha) { "Created commit identity or tree mismatch" }
        val parents = value.getJSONArray("parents")
        require(parents.length() == 1 && sha(parents.getJSONObject(0).getString("sha")) == parentSha) { "Created commit parent mismatch" }
    }

    private fun validateRef(value: JSONObject, branch: String, commitSha: String) {
        val target = value.getJSONObject("object")
        require(value.getString("ref") == "refs/heads/$branch" && target.getString("type") == "commit" &&
            sha(target.getString("sha")) == commitSha) { "Git branch identity mismatch" }
    }

    private fun identity(value: JSONObject): String = sha(value.getString("sha"))
    private fun sha(value: String): String { require(shaPattern.matches(value)) { "Invalid immutable Git identity" }; return value }
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun auditLabel(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/ -]{0,119}"))) { "Invalid provider/model audit label" }
        return value
    }
    private fun validTreePath(path: String): Boolean = pathPattern.matches(path) && !path.startsWith('/') &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." || it.equals(".git", true) }

    internal fun validateEndpoint(method: String, path: String) {
        val get = path.matches(Regex("/git/ref/heads/[A-Za-z0-9._%/-]+")) ||
            path.matches(Regex("/git/commits/[a-f0-9]{40}")) ||
            path.matches(Regex("/git/trees/[a-f0-9]{40}\\?recursive=1")) ||
            path.matches(Regex("/actions/workflows/build-jarvis-developer\\.yml/runs\\?branch=[A-Za-z0-9._%/-]+&head_sha=[a-f0-9]{40}&per_page=20"))
        require((method == "GET" && get) || (method == "POST" && path in setOf("/git/blobs", "/git/trees", "/git/commits", "/git/refs"))) {
            "GitHub operation is outside the scoped coding API"
        }
    }

    private fun blobSha(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
        return digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
}
