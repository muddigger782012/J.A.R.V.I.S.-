package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

class PatchPublisherTest {
    @Test fun twoFileCommitCreatesOneCompleteNewBranchAndPreservesModes() {
        val fixture = Fixture()
        fixture.baseNodes.getValue(KOTLIN).put("mode", "100755")
        val result = fixture.publisher().commit(TOKEN, fixture.approved)
        assertEquals(NEW_COMMIT, result.commitSha)
        assertEquals(BASE, result.baseCommitSha)
        assertEquals("feature/jarvis-developer", result.baseBranch)
        assertEquals(fixture.proposal.id, result.proposalId)
        assertEquals(listOf(KOTLIN, XML).sorted(), result.paths)
        assertEquals(fixture.proposal.diff, result.diff)
        assertEquals(listOf("/git/blobs", "/git/blobs", "/git/trees", "/git/commits", "/git/refs"), fixture.posts().map { it.path })
        val tree = fixture.posts().single { it.path == "/git/trees" }.body!!
        assertEquals(BASE_TREE, tree.getString("base_tree"))
        val entries = tree.getJSONArray("tree")
        assertEquals(2, entries.length())
        assertEquals("100755", (0 until entries.length()).map { entries.getJSONObject(it) }.single { it.getString("path") == KOTLIN }.getString("mode"))
        val commit = fixture.posts().single { it.path == "/git/commits" }.body!!
        assertEquals(NEW_TREE, commit.getString("tree"))
        assertEquals(listOf(BASE), strings(commit.getJSONArray("parents")))
        assertTrue(commit.getString("message").contains(fixture.proposal.id))
        assertFalse(commit.getString("message").contains(fixture.proposal.request.task))
        val ref = fixture.posts().last().body!!
        assertEquals("refs/heads/${result.branch}", ref.getString("ref"))
        assertEquals(NEW_COMMIT, ref.getString("sha"))
        assertEquals(BASE, fixture.baseHead)
        assertEquals(NEW_COMMIT, fixture.branchHead)
        assertTrue(fixture.calls.all { it.method in setOf("GET", "POST") })
    }

    @Test fun staleBaseStopsBeforeWrites() {
        val fixture = Fixture()
        fixture.baseHead = OTHER
        assertFailure("base revision check") { fixture.publisher().commit(TOKEN, fixture.approved) }
        assertTrue(fixture.posts().isEmpty())
    }

    @Test fun repairSourceCreatesOnlyANewSiblingAndRetainsSourceBranchAttribution() {
        val sourceBranch = "feature/jarvis-developer-task/failed-build-123456789abc"
        val fixture = Fixture(sourceBranch = sourceBranch)
        val result = fixture.publisher().commit(TOKEN, fixture.approved)
        assertEquals(sourceBranch, result.baseBranch)
        assertEquals(BASE, result.baseCommitSha)
        assertNotEquals(sourceBranch, result.branch)
        assertEquals(BASE, fixture.baseHead)
        assertEquals(NEW_COMMIT, fixture.branchHead)
        assertEquals(2, fixture.calls.count { it.method == "GET" && it.path == branchPath(sourceBranch) })
        assertFalse(fixture.calls.any { it.path == branchPath("feature/jarvis-developer") })
        assertTrue(fixture.calls.all { it.method == "GET" || (it.method == "POST" && it.path in setOf("/git/blobs", "/git/trees", "/git/commits", "/git/refs")) })
        val commit = fixture.posts().single { it.path == "/git/commits" }.body!!
        assertEquals(listOf(BASE), strings(commit.getJSONArray("parents")))
        assertTrue(commit.getString("message").contains("Source branch: $sourceBranch"))
        val refs = fixture.posts().filter { it.path == "/git/refs" }
        assertEquals(1, refs.size)
        assertEquals("refs/heads/${result.branch}", refs.single().body!!.getString("ref"))
        assertEquals(NEW_COMMIT, refs.single().body!!.getString("sha"))
    }

    @Test fun movedRepairSourceStopsBeforeAnyWriteOrDefaultBranchRead() {
        val sourceBranch = "feature/jarvis-developer-task/failed-build-123456789abc"
        val fixture = Fixture(sourceBranch = sourceBranch)
        fixture.baseHead = OTHER
        assertFailure("base revision check") { fixture.publisher().commit(TOKEN, fixture.approved) }
        assertTrue(fixture.posts().isEmpty())
        assertEquals(listOf(branchPath(sourceBranch)), fixture.calls.map { it.path })
        assertNull(fixture.branchHead)
    }

    @Test fun modifiedProposalInvalidatesApprovalBeforeAnyRequest() {
        val fixture = Fixture()
        @Suppress("UNCHECKED_CAST")
        val edits = fixture.proposal.edits as MutableList<PatchEngine.Edit>
        edits[0] = edits[0].copy(content = edits[0].content + "\n")
        assertFailure { fixture.publisher().commit(TOKEN, fixture.approved) }
        assertTrue(fixture.calls.isEmpty())
    }

    @Test fun changedPreimageStopsBeforeWrites() {
        val fixture = Fixture()
        fixture.baseNodes.getValue(KOTLIN).put("sha", OTHER)
        assertFailure("source preimage validation") { fixture.publisher().commit(TOKEN, fixture.approved) }
        assertTrue(fixture.posts().isEmpty())
    }

    @Test fun existingTaskBranchIsNeverUpdated() {
        val fixture = Fixture()
        fixture.branchHead = OTHER
        assertFailure("task branch collision check") { fixture.publisher().commit(TOKEN, fixture.approved) }
        assertTrue(fixture.posts().isEmpty())
        assertEquals(OTHER, fixture.branchHead)
    }

    @Test fun symlinkTargetIsNotWritten() {
        val fixture = Fixture()
        fixture.baseNodes.getValue(KOTLIN).put("mode", "120000")
        assertFailure("No Git write was attempted") { fixture.publisher().commit(TOKEN, fixture.approved) }
        assertTrue(fixture.posts().isEmpty())
    }

    @Test fun symlinkAncestorIsNotTraversed() {
        val fixture = Fixture()
        fixture.baseNodes.getValue("developer/src/main/java").put("type", "blob").put("mode", "120000")
        assertFailure("No Git write was attempted") { fixture.publisher().commit(TOKEN, fixture.approved) }
        assertTrue(fixture.posts().isEmpty())
    }

    @Test fun truncatedTreeStopsBeforeWrites() {
        val fixture = Fixture()
        fixture.truncated = true
        assertFailure("No Git write was attempted") { fixture.publisher().commit(TOKEN, fixture.approved) }
        assertTrue(fixture.posts().isEmpty())
    }

    @Test fun newFileMustBeAbsentFromBaseTree() {
        val fixture = Fixture(creation = true)
        fixture.baseNodes[NEW_FILE] = node(NEW_FILE, "blob", "100644", OTHER)
        assertFailure("No Git write was attempted") { fixture.publisher().commit(TOKEN, fixture.approved) }
        assertTrue(fixture.posts().isEmpty())
    }

    @Test fun newFileInNewDirectoriesIsCreatedAtTheApprovedPath() {
        val fixture = Fixture(creation = true)
        val result = fixture.publisher().commit(TOKEN, fixture.approved)
        assertEquals(listOf(NEW_FILE), result.paths)
        assertTrue(fixture.newNodes.containsKey("developer/src/main/java/com/jarvis/developer/newfeature"))
        assertEquals("100644", fixture.newNodes.getValue(NEW_FILE).getString("mode"))
    }

    @Test fun blobResponseMustMatchExactApprovedBytes() {
        val fixture = Fixture()
        fixture.wrongBlob = true
        assertFailure("Unreferenced Git objects may exist") { fixture.publisher().commit(TOKEN, fixture.approved) }
        assertFalse(fixture.posts().any { it.path == "/git/trees" || it.path == "/git/refs" })
    }

    @Test fun createdTreeCannotContainAnyUnapprovedChange() {
        val fixture = Fixture()
        fixture.tamperTree = true
        assertFailure("Unreferenced Git objects may exist") { fixture.publisher().commit(TOKEN, fixture.approved) }
        assertFalse(fixture.posts().any { it.path == "/git/commits" || it.path == "/git/refs" })
    }

    @Test fun commitParentAndTreeAreVerifiedBeforeCreatingRef() {
        val fixture = Fixture()
        fixture.wrongParent = true
        assertFailure("Unreferenced Git objects may exist") { fixture.publisher().commit(TOKEN, fixture.approved) }
        assertFalse(fixture.posts().any { it.path == "/git/refs" })
    }

    @Test fun concurrentBaseMoveLeavesOnlyUnreferencedObjects() {
        val fixture = Fixture()
        fixture.moveBaseOnRecheck = true
        assertFailure("no task branch was created") { fixture.publisher().commit(TOKEN, fixture.approved) }
        assertFalse(fixture.posts().any { it.path == "/git/refs" })
        assertNull(fixture.branchHead)
    }

    @Test fun refCollisionIsReportedWithoutRetryOrOverwrite() {
        val fixture = Fixture()
        fixture.collideAtCreate = true
        val failure = assertFailure("HTTP 422") { fixture.publisher().commit(TOKEN, fixture.approved) }
        assertTrue(failure.message!!.contains("outcome is unknown"))
        assertEquals(1, fixture.posts().count { it.path == "/git/refs" })
        assertEquals(OTHER, fixture.branchHead)
        assertEquals(BASE, fixture.baseHead)
    }

    @Test fun refReadbackMustMatchAndFailureNeverDeletesOrUpdatesBranch() {
        val fixture = Fixture()
        fixture.wrongReadback = true
        assertFailure("verify it manually") { fixture.publisher().commit(TOKEN, fixture.approved) }
        assertEquals(1, fixture.posts().count { it.path == "/git/refs" })
        assertTrue(fixture.calls.all { it.method != "PATCH" && it.method != "DELETE" })
    }

    @Test fun failureDoesNotExposeTokenSourceTaskOrApiErrorBody() {
        val fixture = Fixture()
        fixture.failureMessage = "token=$TOKEN ${fixture.proposal.request.task} secret server body"
        val failure = assertFailure("No Git write was attempted") { fixture.publisher().commit(TOKEN, fixture.approved) }
        assertFalse(failure.message!!.contains(TOKEN))
        assertFalse(failure.message!!.contains(fixture.proposal.request.task))
        assertNull(failure.cause)
    }

    @Test fun ciRequiresExactWorkflowBranchAndCommit() {
        val fixture = Fixture()
        val result = fixture.publisher().commit(TOKEN, fixture.approved)
        fixture.runs.put(run(result, "completed", "success").put("head_sha", OTHER))
        fixture.runs.put(run(result, "completed", "success").put("head_branch", "main"))
        fixture.runs.put(run(result, "completed", "success").put("path", ".github/workflows/release-jarvis-developer.yml"))
        assertEquals(PatchPublisher.CiState.MISSING, fixture.publisher().ci(TOKEN, result).state)
        assertTrue(fixture.calls.last().path.contains("head_sha=${result.commitSha}"))
        assertTrue(fixture.calls.last().path.startsWith("/actions/workflows/build-jarvis-developer.yml/runs?"))
    }

    @Test fun ciDistinguishesQueuedRunningSuccessAndFailuresWithoutDispatchingAnything() {
        val fixture = Fixture()
        val result = fixture.publisher().commit(TOKEN, fixture.approved)
        val cases = listOf(
            Triple("queued", "", PatchPublisher.CiState.QUEUED),
            Triple("in_progress", "", PatchPublisher.CiState.RUNNING),
            Triple("completed", "success", PatchPublisher.CiState.PASSED),
            Triple("completed", "failure", PatchPublisher.CiState.FAILED),
            Triple("completed", "cancelled", PatchPublisher.CiState.FAILED),
            Triple("completed", "skipped", PatchPublisher.CiState.FAILED)
        )
        val writes = fixture.posts().size
        for ((status, conclusion, expected) in cases) {
            fixture.runs = JSONArray().put(run(result, status, conclusion))
            val ci = fixture.publisher().ci(TOKEN, result)
            assertEquals(expected, ci.state)
            assertEquals("https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/123", ci.runUrl)
        }
        assertEquals(writes, fixture.posts().size)
    }

    @Test fun latestCiAttemptControlsStatus() {
        val fixture = Fixture()
        val result = fixture.publisher().commit(TOKEN, fixture.approved)
        fixture.runs = JSONArray().put(run(result, "completed", "success").put("run_attempt", 1))
            .put(run(result, "in_progress", "").put("run_attempt", 2))
        assertEquals(PatchPublisher.CiState.RUNNING, fixture.publisher().ci(TOKEN, result).state)
    }

    @Test fun ciRejectsInvalidRepairSourceProvenanceBeforeAnyRequest() {
        val fixture = Fixture(sourceBranch = "feature/jarvis-developer-task/failed-build-123456789abc")
        val result = fixture.publisher().commit(TOKEN, fixture.approved)
        fixture.calls.clear()
        assertFailure("Invalid source branch") { fixture.publisher().ci(TOKEN, result.copy(baseBranch = "main")) }
        assertTrue(fixture.calls.isEmpty())
    }

    @Test fun githubApiDeniesAllUnrelatedOrExistingRefWriteEndpoints() {
        listOf(
            "PATCH" to "/git/refs/heads/feature/jarvis-developer",
            "PUT" to "/contents/developer/src/main/java/Example.kt",
            "DELETE" to "/git/refs/heads/main",
            "POST" to "/releases", "POST" to "/merges", "POST" to "/actions/workflows/build-jarvis-developer.yml/dispatches",
            "POST" to "https://evil.example/git/blobs", "GET" to "/../../user", "GET" to "/git/commits/$BASE#fragment"
        ).forEach { (method, path) -> assertFailure { PatchPublisher.validateEndpoint(method, path) } }
    }

    @Test fun expiredSessionStopsBeforeAnyApiRequest() {
        val fixture = Fixture()
        var clock = 0L
        val publisher = PatchPublisher.Publisher(fixture.api) { clock }
        clock = 180_000L
        assertFailure("No Git write was attempted") { publisher.commit(TOKEN, fixture.approved) }
        assertTrue(fixture.calls.isEmpty())
    }

    private data class Call(val method: String, val path: String, val body: JSONObject?)

    private class Fixture(creation: Boolean = false, private val sourceBranch: String = "feature/jarvis-developer") {
        private val originals = linkedMapOf(
            KOTLIN to "package com.jarvis.developer\nobject PublisherExample { val enabled = false }\n",
            XML to "<resources><string name=\"publisher_example\">Before</string></resources>\n"
        )
        private val changes = if (creation) linkedMapOf(NEW_FILE to "package com.jarvis.developer.newfeature\nobject NewExample { val enabled = true }\n") else linkedMapOf(
            KOTLIN to "package com.jarvis.developer\nobject PublisherExample { val enabled = true }\n",
            XML to "<resources><string name=\"publisher_example\">After</string></resources>\n"
        )
        val proposal: PatchEngine.Proposal
        val approved: PatchEngine.ApprovedProposal
        val baseNodes = linkedMapOf<String, JSONObject>()
        var newNodes = linkedMapOf<String, JSONObject>()
        val calls = arrayListOf<Call>()
        var baseHead = BASE
        var branchHead: String? = null
        var truncated = false
        var wrongBlob = false
        var tamperTree = false
        var wrongParent = false
        var moveBaseOnRecheck = false
        var collideAtCreate = false
        var wrongReadback = false
        var failureMessage: String? = null
        var runs = JSONArray()
        private var baseChecks = 0
        private var refCreated = false
        init {
            originals.forEach { (path, content) ->
                addParents(baseNodes, path)
                baseNodes[path] = node(path, "blob", "100644", blob(content))
            }
            val sources = changes.keys.map { path ->
                val content = originals[path]
                PatchEngine.SourceFile(path, content?.let { blob(it) }, content)
            }
            val request = PatchEngine.Request("publisher-test", "Make a bounded example improvement", BASE,
                changes.keys.toList(), sources, "openai", "test-model", sourceBranch)
            val edits = JSONArray()
            changes.forEach { (path, content) -> edits.put(JSONObject().put("path", path).put("content", content)) }
            proposal = PatchEngine.parse(request, JSONObject().put("summary", "Improve two scoped example sources").put("edits", edits).toString())
            approved = PatchEngine.approve(proposal, proposal.id)
        }

        fun publisher() = PatchPublisher.Publisher(api)
        fun posts() = calls.filter { it.method == "POST" }
        val api = PatchPublisher.GithubApi { method, path, _, payload ->
            calls.add(Call(method, path, payload?.let { JSONObject(it.toString()) }))
            failureMessage?.let { throw IllegalStateException(it) }
            when {
                method == "GET" && path == branchPath(sourceBranch) -> {
                    baseChecks++
                    if (moveBaseOnRecheck && baseChecks > 1) baseHead = OTHER
                    ref(sourceBranch, baseHead)
                }
                method == "GET" && path.startsWith("/git/ref/heads/") -> {
                    assertEquals(branchPath(PatchEngine.branchName(proposal)), path)
                    val head = branchHead ?: throw PatchPublisher.ApiFailure(404)
                    ref(PatchEngine.branchName(proposal), if (wrongReadback && refCreated) OTHER else head)
                }
                method == "GET" && path == "/git/commits/$BASE" -> commit(BASE, BASE_TREE, OTHER)
                method == "GET" && path == "/git/commits/$NEW_COMMIT" -> commit(NEW_COMMIT, NEW_TREE, if (wrongParent) OTHER else BASE)
                method == "GET" && path == "/git/trees/$BASE_TREE?recursive=1" -> tree(BASE_TREE, baseNodes, truncated)
                method == "GET" && path == "/git/trees/$NEW_TREE?recursive=1" -> tree(NEW_TREE, newNodes, false)
                method == "POST" && path == "/git/blobs" -> {
                    assertEquals("base64", payload!!.getString("encoding"))
                    val content = String(Base64.getDecoder().decode(payload.getString("content")), Charsets.UTF_8)
                    JSONObject().put("sha", if (wrongBlob) OTHER else blob(content))
                }
                method == "POST" && path == "/git/trees" -> {
                    newNodes = LinkedHashMap(baseNodes.mapValues { JSONObject(it.value.toString()) })
                    val entries = payload!!.getJSONArray("tree")
                    for (index in 0 until entries.length()) {
                        val entry = entries.getJSONObject(index)
                        val file = entry.getString("path")
                        addParents(newNodes, file)
                        newNodes[file] = JSONObject(entry.toString())
                    }
                    if (tamperTree) newNodes["unapproved.txt"] = node("unapproved.txt", "blob", "100644", OTHER)
                    JSONObject().put("sha", NEW_TREE)
                }
                method == "POST" && path == "/git/commits" -> commit(NEW_COMMIT, NEW_TREE, if (wrongParent) OTHER else BASE)
                method == "POST" && path == "/git/refs" -> {
                    if (collideAtCreate) { branchHead = OTHER; throw PatchPublisher.ApiFailure(422) }
                    assertNull(branchHead)
                    branchHead = payload!!.getString("sha")
                    refCreated = true
                    ref(PatchEngine.branchName(proposal), branchHead!!)
                }
                method == "GET" && path.startsWith("/actions/workflows/") -> JSONObject().put("workflow_runs", runs)
                else -> throw AssertionError("Unexpected scoped GitHub request: $method $path")
            }
        }
    }

    companion object {
        private const val TOKEN = "in-memory-test-token"
        private val BASE = "a".repeat(40)
        private val BASE_TREE = "b".repeat(40)
        private val NEW_TREE = "c".repeat(40)
        private val NEW_COMMIT = "d".repeat(40)
        private val OTHER = "e".repeat(40)
        private const val KOTLIN = "developer/src/main/java/com/jarvis/developer/ExampleStatus.kt"
        private const val XML = "developer/src/main/res/values/example_status.xml"
        private const val NEW_FILE = "developer/src/main/java/com/jarvis/developer/newfeature/NewExample.kt"
        private fun branchPath(branch: String) = "/git/ref/heads/${URLEncoder.encode(branch, "UTF-8")}"
        private fun blob(content: String): String {
            val bytes = content.toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
            return digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        private fun node(path: String, type: String, mode: String, sha: String) = JSONObject()
            .put("path", path).put("type", type).put("mode", mode).put("sha", sha)
        private fun addParents(nodes: MutableMap<String, JSONObject>, path: String) {
            var parent = path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                if (!nodes.containsKey(parent)) nodes[parent] = node(parent, "tree", "040000", OTHER)
                parent = parent.substringBeforeLast('/', "")
            }
        }
        private fun tree(sha: String, nodes: Map<String, JSONObject>, truncated: Boolean): JSONObject = JSONObject()
            .put("sha", sha).put("truncated", truncated).put("tree", JSONArray(nodes.values.toList()))
        private fun commit(sha: String, tree: String, parent: String): JSONObject = JSONObject().put("sha", sha)
            .put("tree", JSONObject().put("sha", tree)).put("parents", JSONArray().put(JSONObject().put("sha", parent)))
        private fun ref(branch: String, sha: String): JSONObject = JSONObject().put("ref", "refs/heads/$branch")
            .put("object", JSONObject().put("type", "commit").put("sha", sha))
        private fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }
        private fun run(result: PatchPublisher.CommitResult, status: String, conclusion: String): JSONObject = JSONObject()
            .put("id", 123L).put("run_number", 1L).put("run_attempt", 1).put("head_sha", result.commitSha)
            .put("head_branch", result.branch).put("path", ".github/workflows/build-jarvis-developer.yml")
            .put("status", status).put("conclusion", conclusion)
        private fun assertFailure(contains: String? = null, block: () -> Unit): Exception {
            try { block() } catch (failure: Exception) {
                if (contains != null) assertTrue("Expected '$contains' in '${failure.message}'", failure.message.orEmpty().contains(contains))
                return failure
            }
            throw AssertionError("Expected scoped operation to fail")
        }
    }
}
