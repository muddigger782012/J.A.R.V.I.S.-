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
        val baseBranch: String = BASE_BRANCH,
        val target: PatchEngine.Target = PatchEngine.Target.DEVELOPER
    )

    enum class CiState { MISSING, QUEUED, RUNNING, PASSED, FAILED }
    data class CiStatus(val state: CiState, val runUrl: String? = null, val conclusion: String? = null)

    /** Read-back identity only. No source, diff, credentials or reusable approval is retained. */
    data class BlobReceipt(val path: String, val before: String?, val after: String)
    data class OutcomeReceipt(val taskId: String, val provider: String, val model: String,
        val proposalId: String, val base: String, val baseBranch: String, val target: PatchEngine.Target,
        val edits: List<BlobReceipt>) {
        val branch get() = PatchEngine.taskBranch(taskId, proposalId, target)
    }
    fun outcomeReceipt(approved: PatchEngine.ApprovedProposal): OutcomeReceipt {
        PatchEngine.validateApproval(approved)
        val p = approved.proposal
        return OutcomeReceipt(p.request.taskId, p.request.provider, p.request.model, p.id,
            p.request.baseCommitSha, p.request.baseBranch, p.request.target, p.edits.map { edit ->
                BlobReceipt(edit.path, p.request.sources.single { it.path == edit.path }.sha,
                    blobSha(edit.content.toByteArray(Charsets.UTF_8)))
            }).also(::validateReceipt)
    }
    internal fun validateReceipt(r: OutcomeReceipt) {
        require(r.taskId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,47}")) &&
            r.proposalId.matches(Regex("[a-f0-9]{64}")) && shaPattern.matches(r.base) &&
            PatchEngine.validSourceBranch(r.baseBranch, r.target))
        auditLabel(r.provider); auditLabel(r.model)
        require(r.edits.size in 1..PatchEngine.MAX_FILES && r.edits.map { it.path }.distinct().size == r.edits.size)
        require(r.edits.all { PatchEngine.allowedPath(it.path, r.target) &&
            (it.before == null || shaPattern.matches(it.before)) && shaPattern.matches(it.after) && it.before != it.after })
    }
    internal fun validateReceiptOutcome(r: OutcomeReceipt, result: CommitResult) {
        validateReceipt(r); validateResult(result)
        require(result.taskId == r.taskId && result.provider == r.provider && result.model == r.model &&
            result.proposalId == r.proposalId && result.baseCommitSha == r.base && result.baseBranch == r.baseBranch &&
            result.target == r.target && result.branch == r.branch && result.paths == r.edits.map { it.path }.sorted())
    }

    /** Error bodies and transport exceptions are deliberately not retained. */
    internal class ApiFailure(val status: Int) : IllegalStateException("GitHub request failed (HTTP $status)")

    /** Paths are relative to the single approved repository, never arbitrary URLs. */
    internal fun interface GithubApi {
        fun request(method: String, path: String, token: String, payload: JSONObject?): JSONObject
    }

    private fun http(cancellation: PatchPreviewCancellation? = null) = GithubApi { method, path, token, payload ->
        validateEndpoint(method, path)
        require((method == "GET" || token.isNotBlank()) && token.length <= 4096 && token.none { it.isISOControl() }) {
            "A valid in-memory GitHub token is required"
        }
        val body = payload?.toString()?.toByteArray(Charsets.UTF_8)
        require((method == "POST") == (body != null)) { "Unexpected GitHub request body" }
        require(body == null || body.size <= MAX_REQUEST_BYTES) { "GitHub request exceeds safety limit" }
        val connection = URL(API + path).openConnection() as HttpURLConnection
        val stop = cancellation?.registerStop { connection.disconnect() }
        val transportStarted = System.nanoTime()
        // Socket read/connect timeouts do not cover a blocked request-body upload.
        val deadline = Timer("JarvisDeveloper-GitHubTimeout", true)
        deadline.schedule(object : TimerTask() {
            override fun run() { try { connection.disconnect() } catch (_: Exception) { /* no background error logging */ } }
        }, MAX_MILLIS)
        try {
            cancellation?.check()
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "JarvisDeveloper-ScopedPatch")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            cancellation?.check()
            val status = connection.responseCode
            if (status !in 200..299) throw ApiFailure(status)
            require(connection.url.toString() == API + path) { "GitHub redirects are not permitted" }
            require(connection.contentLengthLong in -1..MAX_RESPONSE_BYTES.toLong()) { "GitHub response exceeds safety limit" }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(8192)
                var count = 0
                while (true) {
                    cancellation?.check()
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
            stop?.close()
            deadline.cancel()
            connection.disconnect()
        }
    }

    fun commit(token: String, approved: PatchEngine.ApprovedProposal): CommitResult = Publisher(http()).commit(token, approved)
    fun ci(token: String, result: CommitResult): CiStatus = Publisher(http()).ci(token, result)

    /** Observes an interrupted attempt; this operation cannot create objects or restore approval. */
    fun inspectOutcome(token: String, proposal: PatchEngine.Proposal): CommitResult? =
        Publisher(http()).inspectOutcome(token, proposal)

    /** Recovery can observe the approved hashes, but this receipt can never be passed to commit. */
    fun inspectReceipt(token: String, receipt: OutcomeReceipt): CommitResult? = Publisher(http()).inspectReceipt(token, receipt)
    internal fun inspectReceipt(token: String, receipt: OutcomeReceipt, cancellation: PatchPreviewCancellation): CommitResult? =
        Publisher(http(cancellation)).inspectReceipt(token, receipt)

    internal fun validateOutcome(proposal: PatchEngine.Proposal, result: CommitResult) {
        PatchEngine.revalidate(proposal)
        validateResult(result)
        require(result == commitResult(proposal, result.commitSha)) { "Commit outcome does not match this proposal" }
    }

    /** Opens GitHub's review UI only; creating a PR still requires the owner's action there. */
    fun reviewUrl(result: CommitResult): String {
        validateResult(result)
        return "https://github.com/$REPOSITORY/compare/${encode(result.baseBranch)}...${encode(result.branch)}?expand=1"
    }

    internal fun validateResult(result: CommitResult) {
        sha(result.baseCommitSha); sha(result.commitSha)
        require(PatchEngine.validSourceBranch(result.baseBranch, result.target)) { "Invalid source branch" }
        require(result.taskId.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,47}")) &&
            result.proposalId.matches(Regex("[a-f0-9]{64}")) &&
            result.branch == PatchEngine.taskBranch(result.taskId, result.proposalId, result.target) &&
            result.paths.size in 1..PatchEngine.MAX_FILES && result.paths.distinct().size == result.paths.size &&
            result.paths.all { PatchEngine.allowedPath(it, result.target) }) { "Invalid task build identity" }
    }

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
                val expected = expectedTree(proposal, before)
                stage = "unreferenced Git objects"
                for (edit in proposal.edits) {
                    val bytes = edit.content.toByteArray(Charsets.UTF_8)
                    val expectedSha = blobSha(bytes)
                    writesStarted = true
                    val blob = write(token, approved, "/git/blobs", JSONObject()
                        .put("content", Base64.getEncoder().encodeToString(bytes)).put("encoding", "base64"))
                    require(identity(blob) == expectedSha) { "Created blob identity mismatch" }
                    val mode = before[edit.path]?.mode ?: "100644"
                    entries.put(JSONObject().put("path", edit.path).put("type", "blob").put("mode", mode).put("sha", expectedSha))
                }
                val createdTree = write(token, approved, "/git/trees", JSONObject().put("base_tree", baseTree).put("tree", entries))
                val treeSha = identity(createdTree)
                require(treeSha != baseTree) { "Proposal did not create a changed tree" }
                val actual = tree(token, treeSha)
                validateTree(actual, expected)
                val message = commitMessage(proposal)
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
                return commitResult(proposal, commitSha)
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

        /** A 404 is only a missing observation, never proof that a previous write was undone. */
        fun inspectOutcome(token: String, proposal: PatchEngine.Proposal): CommitResult? {
            try {
                PatchEngine.revalidate(proposal)
                val branch = PatchEngine.branchName(proposal)
                val observed = try { head(token, branch) } catch (failure: ApiFailure) {
                    if (failure.status == 404) return null else throw failure
                }
                val base = call("GET", "/git/commits/${proposal.request.baseCommitSha}", token)
                require(identity(base) == proposal.request.baseCommitSha)
                val baseTree = sha(base.getJSONObject("tree").getString("sha"))
                val before = tree(token, baseTree)
                validatePreimages(proposal, before)
                val candidate = call("GET", "/git/commits/$observed", token)
                val candidateTree = sha(candidate.getJSONObject("tree").getString("sha"))
                require(candidateTree != baseTree)
                validateCommit(candidate, observed, candidateTree, proposal.request.baseCommitSha)
                require(candidate.getString("message").removeSuffix("\n") == commitMessage(proposal))
                validateTree(tree(token, candidateTree), expectedTree(proposal, before))
                require(head(token, branch) == observed) { "Task branch changed during verification" }
                return commitResult(proposal, observed).also { validateOutcome(proposal, it) }
            } catch (_: Exception) {
                // No raw response, signed URL, credentials or nested cause escapes this boundary.
                throw IllegalStateException("Read-only commit outcome could not be verified. No write or retry was attempted.")
            }
        }

        fun ci(token: String, result: CommitResult): CiStatus {
            validateResult(result)
            val workflow = if (result.target == PatchEngine.Target.PERSONAL) "local-knowledge-checks.yml" else WORKFLOW
            val path = "/actions/workflows/$workflow/runs?branch=${encode(result.branch)}&head_sha=${result.commitSha}&per_page=20"
            val runs = call("GET", path, token).getJSONArray("workflow_runs")
            require(runs.length() <= 20) { "CI response exceeds requested limit" }
            val matches = (0 until runs.length()).map { runs.getJSONObject(it) }.filter {
                it.optString("head_sha") == result.commitSha && it.optString("head_branch") == result.branch &&
                    it.optString("path").substringBefore('@') == ".github/workflows/$workflow"
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

        fun inspectReceipt(token: String, receipt: OutcomeReceipt): CommitResult? {
            try {
                validateReceipt(receipt)
                val observed = try { head(token, receipt.branch) } catch (failure: ApiFailure) {
                    if (failure.status == 404) return null else throw failure
                }
                val base = call("GET", "/git/commits/${receipt.base}", token)
                require(identity(base) == receipt.base)
                val before = tree(token, sha(base.getJSONObject("tree").getString("sha")))
                val expected = before.toMutableMap()
                for (edit in receipt.edits) {
                    val old = before[edit.path]
                    require(if (edit.before == null) old == null && before.keys.none { it.startsWith(edit.path + "/") }
                        else old?.type == "blob" && old.mode in setOf("100644", "100755") && old.sha == edit.before)
                    var parent = edit.path.substringBeforeLast('/', "")
                    while (parent.isNotEmpty()) {
                        require(before[parent] == null || before[parent]?.type == "tree")
                        expected.putIfAbsent(parent, Node(parent, "tree", "040000", receipt.base))
                        parent = parent.substringBeforeLast('/', "")
                    }
                    expected[edit.path] = Node(edit.path, "blob", old?.mode ?: "100644", edit.after)
                }
                val candidate = call("GET", "/git/commits/$observed", token)
                val candidateTree = sha(candidate.getJSONObject("tree").getString("sha"))
                validateCommit(candidate, observed, candidateTree, receipt.base)
                val message = "Jarvis Developer: scoped task ${receipt.taskId}\n\n" +
                    "Proposal: ${receipt.proposalId}\nBase: ${receipt.base}\nSource branch: ${receipt.baseBranch}\nProduct: ${receipt.target.name}\n" +
                    "Provider: ${auditLabel(receipt.provider)}\nModel: ${auditLabel(receipt.model)}"
                require(candidate.getString("message").removeSuffix("\n") == message)
                validateTree(tree(token, candidateTree), expected)
                require(head(token, receipt.branch) == observed)
                return CommitResult(receipt.taskId, receipt.provider, receipt.model, receipt.proposalId,
                    receipt.edits.map { it.path }.sorted(), receipt.base, observed, receipt.branch, "",
                    receipt.baseBranch, receipt.target).also { validateReceiptOutcome(receipt, it) }
            } catch (_: Exception) {
                throw IllegalStateException("Recorded commit outcome could not be verified. No write or retry was attempted.")
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
                require(PatchEngine.allowedPath(edit.path, proposal.request.target)) { "Path is outside approved write scopes" }
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

        private fun expectedTree(proposal: PatchEngine.Proposal, before: Map<String, Node>): Map<String, Node> =
            before.toMutableMap().apply {
                for (edit in proposal.edits) {
                    this[edit.path] = Node(edit.path, "blob", before[edit.path]?.mode ?: "100644",
                        blobSha(edit.content.toByteArray(Charsets.UTF_8)))
                    var parent = edit.path.substringBeforeLast('/', "")
                    while (parent.isNotEmpty()) {
                        if (!containsKey(parent)) this[parent] = Node(parent, "tree", "040000", "")
                        parent = parent.substringBeforeLast('/', "")
                    }
                }
            }

        private fun validateTree(actual: Map<String, Node>, expected: Map<String, Node>) {
            require(actual.keys == expected.keys && actual.all { (path, node) ->
                val wanted = expected.getValue(path)
                node.type == wanted.type && node.mode == wanted.mode && (node.type == "tree" || node.sha == wanted.sha)
            }) { "Created tree differs from the approved file changes" }
        }
    }

    private fun commitMessage(proposal: PatchEngine.Proposal): String = proposal.request.let { request ->
        "Jarvis Developer: scoped task ${request.taskId}\n\n" +
            "Proposal: ${proposal.id}\nBase: ${request.baseCommitSha}\nSource branch: ${request.baseBranch}\nProduct: ${request.target.name}\n" +
            "Provider: ${auditLabel(request.provider)}\nModel: ${auditLabel(request.model)}"
    }

    private fun commitResult(proposal: PatchEngine.Proposal, sha: String): CommitResult = proposal.request.let { request ->
        CommitResult(request.taskId, request.provider, request.model, proposal.id,
            proposal.edits.map { it.path }.sorted(), request.baseCommitSha, sha, PatchEngine.branchName(proposal),
            proposal.diff, request.baseBranch, request.target)
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
            path.matches(Regex("/actions/workflows/(?:build-jarvis-developer|local-knowledge-checks)\\.yml/runs\\?branch=[A-Za-z0-9._%/-]+&head_sha=[a-f0-9]{40}&per_page=20"))
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
