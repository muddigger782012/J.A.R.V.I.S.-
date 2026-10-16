package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Base64

/**
 * Runs a deterministic provider fixture through the production parser, complete diff, exact
 * approval, atomic Git-data publisher and SHA-bound CI reader. Source contents and Git blob IDs
 * come from the actual immutable checkout. Git tree/commit responses and CI success here are
 * explicitly modeled protocol evidence, not a live AI response, GitHub write or signed build.
 *
 * The first reviewed task adds type counts to the two-file summary feature. Once that feature
 * has been applied, reruns refresh its two reviewed-source comments to the current base SHA;
 * this keeps protocol regression coverage non-noop without claiming another feature delivery.
 */
class PatchAcceptanceTest {
    @Test fun reviewedTwoFileFeatureUsesActualPreimagesAndAtomicNewBranch() {
        val fixture = prepare()
        val before = snapshot(fixture.root)
        try {
            val request = fixture.request
            val proposal = PatchEngine.parse(request, fixture.response)
            assertEquals(2, proposal.edits.size)
            assertEquals(request.allowedPaths.toSet(), proposal.edits.map { it.path }.toSet())
            assertEquals(request.baseCommitSha, proposal.request.baseCommitSha)
            assertEquals("Fixture", proposal.request.provider)
            assertEquals("deterministic-proposal-v1", proposal.request.model)
            assertTrue("Complete review must include both changed paths", request.allowedPaths.all {
                proposal.diff.contains("diff --git a/$it b/$it")
            })
            assertTrue("Feature diff must expose the new type counts or refreshed source audit", proposal.diff.contains("+// Reviewed source: ${request.baseCommitSha}"))
            assertTrue(proposal.edits.single { it.path == SOURCE }.content.contains("Types: "))
            assertTrue(proposal.edits.single { it.path == TEST }.content.contains("countsGradleScriptsBeforeKotlinAndHandlesMixedCase"))
            assertTrue(request.sources.all { source ->
                source.sha == gitBlob(Files.readAllBytes(fixture.root.resolve(source.path))) &&
                    source.content == Files.readString(fixture.root.resolve(source.path))
            })

            val api = ModeledGitApi(request, before)
            assertTrue("Generating and reviewing a diff must not call GitHub", api.calls.isEmpty())
            expectFailure { PatchEngine.approve(proposal, "unreviewed") }
            assertTrue("Rejected approval must not start a GitHub write", api.calls.isEmpty())
            val approved = PatchEngine.approve(proposal, proposal.id)
            PatchEngine.validateApproval(approved)
            api.reviewedId = proposal.id
            val publisher = PatchPublisher.Publisher(api)
            val result = publisher.commit("fixture-in-memory", approved)

            assertEquals(request.taskId, result.taskId)
            assertEquals(request.provider, result.provider)
            assertEquals(request.model, result.model)
            assertEquals(request.baseCommitSha, result.baseCommitSha)
            assertEquals(proposal.id, result.proposalId)
            assertEquals(request.allowedPaths.sorted(), result.paths)
            assertEquals(proposal.diff, result.diff)
            assertEquals(PatchEngine.branchName(proposal), result.branch)
            assertTrue(result.branch.startsWith("feature/jarvis-developer-task/"))
            assertEquals(api.createdCommitSha, result.commitSha)
            assertNotEquals("Task feature must create a separate commit", result.baseCommitSha, result.commitSha)
            assertEquals("Base branch must remain unchanged", request.baseCommitSha, api.baseBranchHead)
            assertEquals("Only the new isolated task ref may become visible", listOf("refs/heads/${result.branch}"), api.createdRefs)
            assertEquals("Two blobs, one tree, one commit, then one new ref are the only writes",
                listOf("/git/blobs", "/git/blobs", "/git/trees", "/git/commits", "/git/refs"),
                api.calls.filter { it.method == "POST" }.map { it.path })
            assertEquals(request.allowedPaths.toSet(), api.editedPaths.toSet())
            assertEquals(2, api.editedPaths.size)
            assertTrue(api.calls.all { it.method == "GET" || it.method == "POST" })
            assertFalse("No release, workflow dispatch, merge, installation or ref update endpoint",
                api.calls.any { call -> listOf("release", "dispatch", "merge", "install").any { call.path.contains(it) } || call.method in listOf("PATCH", "PUT", "DELETE") })
            assertTrue("Commit attribution must bind provider/model, task, proposal and base", listOf(
                request.taskId, request.provider, request.model, proposal.id, request.baseCommitSha
            ).all { api.commitMessage.contains(it) })
            proposal.edits.forEach { edit ->
                assertEquals("Posted blob must contain exactly the reviewed bytes", edit.content, api.createdContents.getValue(gitBlob(edit.content.toByteArray(Charsets.UTF_8))))
            }

            val ci = publisher.ci("fixture-in-memory", result)
            assertEquals(PatchPublisher.CiState.PASSED, ci.state)
            assertEquals("success", ci.conclusion)
            assertEquals("https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/42", ci.runUrl)
            val ciCall = api.calls.last()
            assertEquals("GET", ciCall.method)
            assertTrue("CI must query the exact reviewed commit", ciCall.path.contains("head_sha=${result.commitSha}"))
            assertEquals("CI must query the exact task branch", result.branch,
                URLDecoder.decode(ciCall.path.substringAfter("branch=").substringBefore('&'), "UTF-8"))

            emitReviewArtifacts(fixture, proposal, api)
        } finally {
            assertEquals("Proposal and modeled commit must leave all checkout source paths and bytes unchanged", before, snapshot(fixture.root))
        }
    }

    @Test fun existingTaskBranchFailsBeforeAnyGitWrite() {
        val fixture = prepare()
        val before = snapshot(fixture.root)
        try {
            val proposal = PatchEngine.parse(fixture.request, fixture.response)
            val api = ModeledGitApi(fixture.request, before, branchAlreadyExists = true)
            api.reviewedId = proposal.id
            expectFailure { PatchPublisher.Publisher(api).commit("fixture-in-memory", PatchEngine.approve(proposal, proposal.id)) }
            assertTrue("A collision must not overwrite any ref or create objects", api.calls.none { it.method != "GET" })
            assertEquals(fixture.request.baseCommitSha, api.baseBranchHead)
            assertTrue(api.createdRefs.isEmpty())
        } finally {
            assertEquals(before, snapshot(fixture.root))
        }
    }

    @Test fun changedCheckoutPreimageFailsBeforeAnyGitWrite() {
        val fixture = prepare()
        val before = snapshot(fixture.root)
        try {
            val proposal = PatchEngine.parse(fixture.request, fixture.response)
            val api = ModeledGitApi(fixture.request, before, wrongPreimage = true)
            api.reviewedId = proposal.id
            expectFailure { PatchPublisher.Publisher(api).commit("fixture-in-memory", PatchEngine.approve(proposal, proposal.id)) }
            assertTrue("An unexpected source blob must fail before writes", api.calls.none { it.method != "GET" })
            assertTrue(api.createdRefs.isEmpty())
        } finally {
            assertEquals(before, snapshot(fixture.root))
        }
    }

    private data class Fixture(val root: Path, val request: PatchEngine.Request, val response: String)

    private fun prepare(): Fixture {
        val root = Paths.get(requireNotNull(System.getProperty("jarvis.repositoryRoot")) {
            "Set jarvis.repositoryRoot to the immutable analyzed checkout"
        }).toRealPath()
        val commit = System.getProperty("jarvis.repositoryCommit").orEmpty()
        assertTrue("Acceptance requires the explicit full checkout GITHUB_SHA", Regex("[a-f0-9]{40}").matches(commit))
        val paths = listOf(SOURCE, TEST)
        val sources = paths.map { path ->
            val file = root.resolve(path)
            assertTrue("Representative preimage must be a real checkout file: $path", Files.isRegularFile(file) && !Files.isSymbolicLink(file))
            val bytes = Files.readAllBytes(file)
            PatchEngine.SourceFile(path, gitBlob(bytes), bytes.toString(Charsets.UTF_8))
        }
        val request = PatchEngine.Request(
            taskId = "phase2-file-summary",
            task = "Add Kotlin, Java, XML and Gradle type counts to the Developer task file summary. Preserve distinct sorted paths and cover type categorization, duplicates and empty scopes with tests.",
            baseCommitSha = commit,
            allowedPaths = paths,
            sources = sources,
            provider = "Fixture",
            model = "deterministic-proposal-v1"
        )
        val response = JSONObject().put("summary", "Add source type counts to the Developer file review summary and verify them with focused unit tests. Deterministic provider fixture; no live AI request.")
            .put("edits", JSONArray().put(JSONObject().put("path", SOURCE).put("content", enhancedSource(commit)))
                .put(JSONObject().put("path", TEST).put("content", enhancedTests(commit))))
            .toString()
        return Fixture(root, request, response)
    }

    private fun emitReviewArtifacts(fixture: Fixture, proposal: PatchEngine.Proposal, api: ModeledGitApi) {
        val reports = fixture.root.resolve("developer/build/reports/phase2-acceptance")
        Files.createDirectories(reports)
        val request = proposal.request
        val json = JSONObject().put("schema", "jarvis-phase2-reviewed-proposal-v1")
            .put("taskId", request.taskId).put("task", request.task).put("baseCommitSha", request.baseCommitSha)
            .put("provider", request.provider).put("model", request.model)
            .put("allowedPaths", JSONArray(request.allowedPaths))
            .put("sources", JSONArray(request.sources.map { source -> JSONObject()
                .put("path", source.path).put("sha", source.sha).put("content", source.content) }))
            .put("edits", JSONArray(proposal.edits.map { edit -> JSONObject().put("path", edit.path).put("content", edit.content) }))
            .put("summary", proposal.summary).put("proposalId", proposal.id).put("diff", proposal.diff)
            .put("branchName", PatchEngine.branchName(proposal))
            .put("providerResponse", fixture.response)
            .put("evidence", JSONObject()
                .put("source", "Actual immutable checkout contents and computed Git blob SHA-1 IDs")
                .put("provider", "Deterministic fixture; no live AI request")
                .put("gitProtocol", "In-memory modeled GitHub tree/commit/ref responses; not actual Git root metadata or remote writes")
                .put("ci", "Modeled SHA-bound CI protocol response; not a passing real build")
                .put("liveWriteStatus", "Requires separate complete diff review before any live task-branch commit")
                .put("reruns", "After feature integration, only reviewed-source comments refresh; protocol coverage is not another feature delivery"))
        Files.writeString(reports.resolve("ready-proposal.json"), json.toString(2) + "\n")
        Files.writeString(reports.resolve("review.diff"), proposal.diff)
        Files.writeString(reports.resolve("modeled-protocol.json"), JSONObject()
            .put("evidenceType", "modeled protocol only; no live GitHub writes or CI result")
            .put("calls", JSONArray(api.calls.map { JSONObject().put("method", it.method).put("path", it.path) }))
            .put("proposalId", proposal.id).put("baseCommitSha", request.baseCommitSha)
            .put("modeledCommitSha", api.createdCommitSha).put("modeledTreeSha", api.createdTreeSha).toString(2) + "\n")
    }

    private data class Call(val method: String, val path: String)
    private data class Node(val path: String, val type: String, val mode: String, val sha: String)

    /** Full tree shape is modeled; leaf blob IDs are computed from actual checkout bytes. */
    private class ModeledGitApi(
        private val request: PatchEngine.Request,
        snapshot: Map<String, String>,
        private val branchAlreadyExists: Boolean = false,
        wrongPreimage: Boolean = false
    ) : PatchPublisher.GithubApi {
        val calls = mutableListOf<Call>()
        val createdRefs = mutableListOf<String>()
        val editedPaths = mutableListOf<String>()
        val createdContents = mutableMapOf<String, String>()
        var reviewedId: String? = null
        var commitMessage = ""
        val baseBranchHead = request.baseCommitSha
        private val baseTreeSha = modeledSha("base-tree:${request.baseCommitSha}")
        val createdTreeSha = modeledSha("new-tree:${request.baseCommitSha}")
        val createdCommitSha = modeledSha("new-commit:${request.baseCommitSha}")
        private var branch: String? = null
        private var newTree: List<Node>? = null
        private var commitObject: JSONObject? = null
        private val oldTree: List<Node> = run {
            val nodes = sortedMapOf<String, Node>()
            snapshot.forEach { (path, blob) ->
                nodes[path] = Node(path, "blob", "100644", blob)
                var parent = path.substringBeforeLast('/', "")
                while (parent.isNotEmpty()) {
                    nodes[parent] = Node(parent, "tree", "040000", modeledSha("directory:$parent"))
                    parent = parent.substringBeforeLast('/', "")
                }
            }
            if (wrongPreimage) nodes[SOURCE] = nodes.getValue(SOURCE).copy(sha = modeledSha("changed-source"))
            nodes.values.toList()
        }

        override fun request(method: String, path: String, token: String, payload: JSONObject?): JSONObject {
            assertEquals("No credential or network is used by this fixture", "fixture-in-memory", token)
            assertEquals(method == "POST", payload != null)
            calls += Call(method, path)
            if (method == "POST") assertTrue("Only an exactly reviewed proposal may cause a write", reviewedId?.matches(Regex("[a-f0-9]{64}")) == true)
            if (method == "GET" && path.startsWith("/git/ref/heads/")) {
                val name = URLDecoder.decode(path.substringAfter("/git/ref/heads/"), "UTF-8")
                return when {
                    name == "feature/jarvis-developer" -> ref(name, baseBranchHead)
                    name == branch -> ref(name, createdCommitSha)
                    branchAlreadyExists -> ref(name, request.baseCommitSha)
                    else -> throw PatchPublisher.ApiFailure(404)
                }
            }
            if (method == "GET" && path == "/git/commits/${request.baseCommitSha}") {
                return JSONObject().put("sha", request.baseCommitSha).put("tree", JSONObject().put("sha", baseTreeSha))
            }
            if (method == "GET" && path == "/git/trees/$baseTreeSha?recursive=1") return tree(baseTreeSha, oldTree)
            if (method == "POST" && path == "/git/blobs") {
                val body = requireNotNull(payload)
                assertEquals(setOf("content", "encoding"), body.keySet())
                assertEquals("base64", body.getString("encoding"))
                val bytes = Base64.getDecoder().decode(body.getString("content"))
                val content = bytes.toString(Charsets.UTF_8)
                val blob = gitBlob(bytes)
                createdContents[blob] = content
                return JSONObject().put("sha", blob)
            }
            if (method == "POST" && path == "/git/trees") {
                val body = requireNotNull(payload)
                assertEquals(setOf("base_tree", "tree"), body.keySet())
                assertEquals(baseTreeSha, body.getString("base_tree"))
                val edits = body.getJSONArray("tree")
                assertEquals("Exactly two reviewed files must be committed atomically", 2, edits.length())
                val nodes = oldTree.associateBy { it.path }.toMutableMap()
                for (index in 0 until edits.length()) {
                    val edit = edits.getJSONObject(index)
                    val changed = edit.getString("path")
                    assertTrue("Tree edit escaped task scope", changed in request.allowedPaths)
                    assertEquals(setOf("path", "type", "mode", "sha"), edit.keySet())
                    assertEquals("blob", edit.getString("type"))
                    assertEquals("100644", edit.getString("mode"))
                    val blob = edit.getString("sha")
                    assertTrue("Tree must reference one of the reviewed created blobs", blob in createdContents)
                    editedPaths += changed
                    nodes[changed] = Node(changed, "blob", "100644", blob)
                }
                assertEquals(request.allowedPaths.toSet(), editedPaths.toSet())
                newTree = nodes.values.sortedBy { it.path }
                return JSONObject().put("sha", createdTreeSha)
            }
            if (method == "GET" && path == "/git/trees/$createdTreeSha?recursive=1") return tree(createdTreeSha, requireNotNull(newTree))
            if (method == "POST" && path == "/git/commits") {
                val body = requireNotNull(payload)
                assertEquals(setOf("message", "tree", "parents"), body.keySet())
                assertEquals(createdTreeSha, body.getString("tree"))
                assertEquals(listOf(request.baseCommitSha), body.getJSONArray("parents").let { (0 until it.length()).map(it::getString) })
                commitMessage = body.getString("message")
                assertTrue(commitMessage.contains(requireNotNull(reviewedId)))
                commitObject = JSONObject().put("sha", createdCommitSha).put("tree", JSONObject().put("sha", createdTreeSha))
                    .put("parents", JSONArray().put(JSONObject().put("sha", request.baseCommitSha)))
                return JSONObject(requireNotNull(commitObject).toString())
            }
            if (method == "GET" && path == "/git/commits/$createdCommitSha") return JSONObject(requireNotNull(commitObject).toString())
            if (method == "POST" && path == "/git/refs") {
                val body = requireNotNull(payload)
                assertEquals(setOf("ref", "sha"), body.keySet())
                assertEquals(createdCommitSha, body.getString("sha"))
                val value = body.getString("ref")
                assertEquals("refs/heads/feature/jarvis-developer-task/${request.taskId}-${requireNotNull(reviewedId).take(12)}", value)
                assertTrue("The branch must not be visible until all objects are verified", createdRefs.isEmpty())
                createdRefs += value
                branch = value.removePrefix("refs/heads/")
                return ref(requireNotNull(branch), createdCommitSha)
            }
            if (method == "GET" && path.startsWith("/actions/workflows/build-jarvis-developer.yml/runs?")) {
                assertTrue(path.contains("head_sha=$createdCommitSha"))
                val valid = JSONObject().put("id", 42).put("run_number", 1).put("run_attempt", 1)
                    .put("head_sha", createdCommitSha).put("head_branch", requireNotNull(branch))
                    .put("path", ".github/workflows/build-jarvis-developer.yml")
                    .put("status", "completed").put("conclusion", "success")
                // A newer unrelated failure must not override the matching task SHA/branch.
                val foreign = JSONObject(valid.toString()).put("id", 43).put("run_number", 100)
                    .put("head_sha", request.baseCommitSha).put("conclusion", "failure")
                return JSONObject().put("workflow_runs", JSONArray().put(foreign).put(valid))
            }
            throw AssertionError("Unexpected modeled GitHub operation: $method $path")
        }

        private fun ref(name: String, sha: String) = JSONObject().put("ref", "refs/heads/$name")
            .put("object", JSONObject().put("type", "commit").put("sha", sha))

        private fun tree(sha: String, nodes: List<Node>) = JSONObject().put("sha", sha).put("truncated", false)
            .put("tree", JSONArray(nodes.map { node -> JSONObject().put("path", node.path).put("type", node.type)
                .put("mode", node.mode).put("sha", node.sha) }))
    }

    private fun snapshot(root: Path): Map<String, String> {
        val files = sortedMapOf<String, String>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (directory != root && directory.fileName.toString() in
                    setOf(".git", ".gradle", ".kotlin", "build", "out", "node_modules")) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (attributes.isRegularFile && !Files.isSymbolicLink(file) && file.fileName.toString() != ".git") {
                    files[root.relativize(file).toString().replace('\\', '/')] = gitBlob(Files.readAllBytes(file))
                }
                return FileVisitResult.CONTINUE
            }
        })
        return files
    }

    private fun enhancedSource(commit: String): String = """
        package com.jarvis.developer

        // Reviewed source: $commit
        /** A deterministic review summary; it never reads or changes source files. */
        object TaskFileSummary {
            fun describe(paths: List<String>): String {
                val files = paths.distinct().sorted()
                val counts = linkedMapOf("Kotlin" to 0, "Java" to 0, "XML" to 0, "Gradle" to 0, "Other" to 0)
                files.forEach { path ->
                    val name = path.substringAfterLast('/').lowercase(java.util.Locale.ROOT)
                    val type = when {
                        name.endsWith(".gradle") || name.endsWith(".gradle.kts") -> "Gradle"
                        name.endsWith(".kt") || name.endsWith(".kts") -> "Kotlin"
                        name.endsWith(".java") -> "Java"
                        name.endsWith(".xml") -> "XML"
                        else -> "Other"
                    }
                    counts[type] = counts.getValue(type) + 1
                }
                val count = "${'$'}{files.size} ${'$'}{if (files.size == 1) "file" else "files"}"
                val types = "Types: " + counts.entries.joinToString(", ") { "${'$'}{it.key} ${'$'}{it.value}" }
                return (listOf(count, types) + files).joinToString("\n")
            }
        }
    """.trimIndent() + "\n"

    private fun enhancedTests(commit: String): String = """
        package com.jarvis.developer

        import org.junit.Assert.assertEquals
        import org.junit.Test

        // Reviewed source: $commit
        class TaskFileSummaryTest {
            @Test fun summarizesDistinctPathsInStableOrder() {
                assertEquals("2 files\nTypes: Kotlin 1, Java 0, XML 1, Gradle 0, Other 0\na.kt\nz.xml",
                    TaskFileSummary.describe(listOf("z.xml", "a.kt", "z.xml")))
            }

            @Test fun summarizesEmptyAndSingleFileScopes() {
                assertEquals("0 files\nTypes: Kotlin 0, Java 0, XML 0, Gradle 0, Other 0", TaskFileSummary.describe(emptyList()))
                assertEquals("1 file\nTypes: Kotlin 0, Java 1, XML 0, Gradle 0, Other 0\nMain.java",
                    TaskFileSummary.describe(listOf("Main.java")))
            }

            @Test fun countsGradleScriptsBeforeKotlinAndHandlesMixedCase() {
                val paths = listOf("src/Main.KT", "src/Thing.JAVA", "res/layout.xml", "build.gradle.kts",
                    "legacy.gradle", "script.kts", "README", "src/Main.KT")
                val expected = listOf("7 files", "Types: Kotlin 2, Java 1, XML 1, Gradle 2, Other 1") + paths.distinct().sorted()
                assertEquals(expected.joinToString("\n"), TaskFileSummary.describe(paths))
            }

            @Test fun classifiesTheFilenameInsteadOfParentDirectory() {
                assertEquals("1 file\nTypes: Kotlin 0, Java 0, XML 0, Gradle 0, Other 1\nfolder.kt/readme",
                    TaskFileSummary.describe(listOf("folder.kt/readme")))
            }
        }
    """.trimIndent() + "\n"

    private fun expectFailure(action: () -> Unit) {
        var failed = false
        try { action() } catch (_: IllegalArgumentException) { failed = true } catch (_: IllegalStateException) { failed = true }
        assertTrue("Expected the operation to fail closed", failed)
    }

    companion object {
        private const val SOURCE = "developer/src/main/java/com/jarvis/developer/TaskFileSummary.kt"
        private const val TEST = "developer/src/test/java/com/jarvis/developer/TaskFileSummaryTest.kt"

        private fun gitBlob(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
            return digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        }

        private fun modeledSha(label: String): String = MessageDigest.getInstance("SHA-1")
            .digest("MODELED ONLY:$label".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
