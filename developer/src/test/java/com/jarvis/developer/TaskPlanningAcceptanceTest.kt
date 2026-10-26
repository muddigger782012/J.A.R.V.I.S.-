package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
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
 * Real immutable checkout -> production GET-only analysis -> two independent local-store
 * sessions. Step completion below is a simulated user report, never compiler/CI evidence.
 * No provider, network, Git write or repository edit capability is supplied to the planner.
 */
class TaskPlanningAcceptanceTest {
    @Test fun scopedQueueSurvivesTwoSessionsAndRecoversInterruptedWorkWithoutApproval() {
        val root = Paths.get(requireNotNull(System.getProperty("jarvis.repositoryRoot")) {
            "Set jarvis.repositoryRoot to the immutable analyzed checkout"
        }).toRealPath()
        val commit = System.getProperty("jarvis.repositoryCommit").orEmpty()
        assertTrue("Acceptance requires an explicit full checkout GITHUB_SHA", Regex("[a-f0-9]{40}").matches(commit))
        val before = snapshot(root)
        val scope = listOf(SOURCE, TEST)
        val sourceBytes = scope.associateWith { path ->
            val file = root.resolve(path)
            assertTrue("Task scope must use actual regular checkout files: $path", Files.isRegularFile(file) && !Files.isSymbolicLink(file))
            Files.readAllBytes(file)
        }
        val stateDirectory = Files.createTempDirectory("jarvis-phase3-session-")
        assertFalse("Task state must be outside the analyzed repository", stateDirectory.startsWith(root))
        try {
            val fixture = CheckoutGetFixture(root, commit, sourceBytes)
            val (inventory, analysis) = RepositoryIntelligence.Reader(fixture).analyzeSnapshot("", TASK)
            assertEquals(commit, inventory.commitSha)
            assertEquals(scope.sorted(), inventory.files.map { it.path }.sorted())
            assertEquals("Both source files need relevant retrieved evidence", scope.toSet(), analysis.files.map { it.path }.toSet())
            assertEquals(commit, analysis.commitSha)
            assertEquals(5, fixture.requests.size)
            assertEquals("Every modeled GET must close", fixture.requests.size, fixture.closedResponses)
            assertEquals(scope.toSet(), fixture.sourceReads.toSet())
            analysis.files.forEach { file ->
                assertEquals(gitBlob(sourceBytes.getValue(file.path)), file.source.sha)
                assertTrue("Analysis must include actual source references", file.citations.isNotEmpty())
                assertTrue(file.citations.all { it.path == file.path && it.commitSha == commit && it.blobSha == file.source.sha })
            }
            assertTrue("The test's real same-package source dependency must inform decomposition", analysis.files.single { it.path == TEST }
                .dependencies.any { SOURCE in it.resolvedPaths && it.status == "resolved" })

            var clock = 1_800_000_000_000L
            fun tick(): Long { clock += 1; return clock }
            val session1 = TaskPlanStore(stateDirectory.toFile())
            assertTrue(session1.loadAll().isEmpty())
            var plan = TaskPlanner.create("phase3-file-summary", analysis, scope, tick())
            assertEquals(0L, plan.revision)
            assertEquals(scope.sorted(), plan.scope)
            assertEquals(analysis.analysisId, plan.analysisId)
            assertEquals(commit, plan.baseCommitSha)
            assertEquals(scope.toSet(), plan.steps.flatMap { it.paths }.toSet())
            session1.save(plan, null)
            val revisions = mutableListOf(plan.revision)
            fun saveSession1(changed: TaskPlanner.Plan) {
                assertEquals(plan.revision + 1, changed.revision)
                session1.save(changed, plan.revision)
                plan = changed
                revisions += changed.revision
            }
            saveSession1(TaskPlanner.note(plan,
                "Keep distinct sorted paths and Kotlin, Java, XML and Gradle counts. Scope stays limited to the summary and its regression tests.", tick()))
            val sourceStep = plan.steps.single { SOURCE in it.paths }
            val testStep = plan.steps.single { TEST in it.paths }
            assertTrue("Initial queue must respect the actual source dependency", sourceStep.id in testStep.dependsOn)
            saveSession1(TaskPlanner.editStep(plan, testStep.id, "Draft summary regression assertions first",
                "Specify type-count, duplicate-path and empty-scope expectations before implementation; validate compilation only after both scoped edits.",
                listOf("analyze"), tick()))
            saveSession1(TaskPlanner.move(plan, testStep.id, -1, tick()))
            assertTrue(plan.steps.indexOfFirst { it.id == testStep.id } < plan.steps.indexOfFirst { it.id == sourceStep.id })
            val editedQueue = plan.steps.map { it.id to Triple(it.title, it.detail, it.dependsOn) }

            val analyze = requireNotNull(TaskPlanner.next(plan))
            assertEquals("analyze", analyze.id)
            saveSession1(TaskPlanner.transition(plan, analyze.id, TaskPlanner.Status.RUNNING, "", tick()))
            saveSession1(TaskPlanner.transition(plan, analyze.id, TaskPlanner.Status.DONE,
                "User report: reviewed the two scoped paths and immutable source references; no source changes applied.", tick()))
            assertEquals(testStep.id, requireNotNull(TaskPlanner.next(plan)).id)
            saveSession1(TaskPlanner.transition(plan, testStep.id, TaskPlanner.Status.RUNNING, "", tick()))
            saveSession1(TaskPlanner.transition(plan, testStep.id, TaskPlanner.Status.DONE,
                "User report: regression expectations recorded for this planning exercise; no test command executed by the planner.", tick()))
            assertEquals(sourceStep.id, requireNotNull(TaskPlanner.next(plan)).id)
            saveSession1(TaskPlanner.transition(plan, sourceStep.id, TaskPlanner.Status.RUNNING, "", tick()))

            // A separate, exact-approved diff exists only in session memory. Saving a plan must
            // not serialize this authority, its source preimages or its replacement bytes.
            val proposal = sessionOnlyProposal(commit, sourceBytes)
            val ephemeralApproval = PatchEngine.approve(proposal, proposal.id)
            PatchEngine.validateApproval(ephemeralApproval)
            val checkpointFiles = stateDirectory.toFile().walkTopDown().filter { it.isFile }.toList()
            assertEquals("Only the metadata checkpoint should exist", 1, checkpointFiles.size)
            checkpointFiles.forEach { file ->
                val bytes = file.readBytes()
                val text = bytes.toString(Charsets.UTF_8)
                assertFalse("Exact diff approval must not survive in task state", text.contains(proposal.id))
                assertFalse("Diffs must stay outside task checkpoints", text.contains("diff --git") || text.contains(proposal.diff))
                assertFalse("Checkpoint must not contain proposal or approval records", text.contains("ApprovedProposal") ||
                    text.contains("\"approvalId\"") || text.contains("\"proposalId\""))
                assertTrue("Full source contents must stay outside task checkpoints", sourceBytes.values.none { text.contains(it.toString(Charsets.UTF_8)) })
                assertTrue("Source excerpts must stay outside task checkpoints", listOf("package com.jarvis.developer",
                    "object TaskFileSummary {", "class TaskFileSummaryTest {").none { text.contains(it) })
                assertTrue("Replacement source bytes must not be saved as task metadata", proposal.edits.none { text.contains(it.content) })
            }
            val saved = plan
            val savedCheckpointHash = sha256(checkpointFiles.single().readBytes())

            // Fresh object and disk decode model a second process/session, with no in-memory
            // proposal or approval passed into the durable task API.
            val session2 = TaskPlanStore(stateDirectory.toFile())
            var resumed = session2.loadAll().single()
            assertEquals("Reload must preserve the exact task metadata", saved, resumed)
            assertEquals("Reload must preserve user queue edits", editedQueue,
                resumed.steps.map { it.id to Triple(it.title, it.detail, it.dependsOn) })
            assertEquals(saved.decisions, resumed.decisions)
            assertEquals(saved.scope, resumed.scope)
            assertEquals(saved.baseCommitSha, resumed.baseCommitSha)
            assertEquals(saved.analysisId, resumed.analysisId)
            assertEquals(TaskPlanner.Status.RUNNING, resumed.steps.single { it.id == sourceStep.id }.status)
            assertEquals(savedCheckpointHash, sha256(checkpointFiles.single().readBytes()))
            fun saveSession2(changed: TaskPlanner.Plan) {
                assertEquals(resumed.revision + 1, changed.revision)
                session2.save(changed, resumed.revision)
                resumed = changed
                revisions += changed.revision
            }

            saveSession2(TaskPlanner.recover(resumed, tick()))
            val recovered = resumed
            assertTrue("Recovery must pause instead of continuing uncertain work", resumed.paused)
            assertNull(TaskPlanner.next(resumed))
            assertEquals(TaskPlanner.Status.BLOCKED, resumed.steps.single { it.id == sourceStep.id }.status)
            assertTrue(resumed.steps.single { it.id == sourceStep.id }.checkpoint.isEmpty())
            assertEquals(TaskPlanner.Status.DONE, resumed.steps.single { it.id == testStep.id }.status)
            assertTrue("Recovery must disclose uncertainty and lost authority", resumed.decisions.last().message.let {
                it.contains("uncertain") && it.contains("No write approval")
            })
            assertEquals(saved.decisions, resumed.decisions.take(saved.decisions.size))
            saveSession2(TaskPlanner.note(resumed,
                "User acknowledged the interrupted implementation has no verified outcome. Reinspect originals and obtain a fresh diff approval before any write.", tick()))
            saveSession2(TaskPlanner.transition(resumed, sourceStep.id, TaskPlanner.Status.PENDING, "", tick()))
            assertNull("Requeue alone cannot resume a paused task", TaskPlanner.next(resumed))
            saveSession2(TaskPlanner.pause(resumed, false, tick()))
            val completionOrder = mutableListOf<String>()
            repeat(TaskPlanner.MAX_STEPS) {
                val next = TaskPlanner.next(resumed)
                if (next != null) {
                    val doneBefore = resumed.steps.filter { it.status == TaskPlanner.Status.DONE }.map { it.id }.toSet()
                    assertTrue("Resumption must obey the edited dependency graph", next.dependsOn.all { it in doneBefore })
                    saveSession2(TaskPlanner.transition(resumed, next.id, TaskPlanner.Status.RUNNING, "", tick()))
                    saveSession2(TaskPlanner.transition(resumed, next.id, TaskPlanner.Status.DONE,
                        "User-reported planning simulation complete for ${next.id}; no repository write, build or CI result is implied.", tick()))
                    completionOrder += next.id
                }
            }
            assertTrue("All planned steps must be resumable", resumed.steps.all { it.status == TaskPlanner.Status.DONE })
            assertNull(TaskPlanner.next(resumed))
            assertEquals(sourceStep.id, completionOrder.first())
            assertEquals("review", completionOrder.last())
            assertEquals(scope.sorted(), resumed.scope)
            assertEquals(commit, resumed.baseCommitSha)
            assertEquals(analysis.analysisId, resumed.analysisId)
            assertEquals((0L..resumed.revision).toList(), revisions)
            assertEquals((1..resumed.decisions.size).toList(), resumed.decisions.map { it.sequence })
            assertEquals("A further fresh load must retain the completed plan", resumed,
                TaskPlanStore(stateDirectory.toFile()).loadAll().single())
            assertNotEquals(saved.revision, resumed.revision)
            assertEquals("Planning/checkpointing must leave all checkout paths and bytes unchanged", before, snapshot(root))
            emitEvidence(root, analysis, sourceBytes, fixture, saved, recovered, resumed, savedCheckpointHash, completionOrder)
        } finally {
            assertEquals("Analysis, store reload and recovery must not modify the analyzed repository", before, snapshot(root))
            assertTrue("Temporary private task state should be removable", stateDirectory.toFile().deleteRecursively())
        }
    }

    private fun sessionOnlyProposal(commit: String, sourceBytes: Map<String, ByteArray>): PatchEngine.Proposal {
        val request = PatchEngine.Request("phase3-session-approval", TASK, commit, listOf(SOURCE, TEST),
            listOf(SOURCE, TEST).map { path -> PatchEngine.SourceFile(path, gitBlob(sourceBytes.getValue(path)), sourceBytes.getValue(path).toString(Charsets.UTF_8)) },
            "Fixture", "deterministic-session-v1")
        val response = JSONObject().put("summary", "Session-only proposal to prove exact diff approval is excluded from persisted planning metadata; no write attempted.")
            .put("edits", JSONArray(listOf(SOURCE, TEST).map { path -> JSONObject().put("path", path)
                .put("content", sourceBytes.getValue(path).toString(Charsets.UTF_8) + "\n// Session-only phase 3 proposal; never applied.\n") }))
        return PatchEngine.parse(request, response.toString())
    }

    private fun emitEvidence(root: Path, analysis: RepositoryAnalysis.AnalysisPlan, sources: Map<String, ByteArray>,
        fixture: CheckoutGetFixture, saved: TaskPlanner.Plan, recovered: TaskPlanner.Plan, completed: TaskPlanner.Plan,
        savedCheckpointHash: String, completionOrder: List<String>) {
        fun summary(plan: TaskPlanner.Plan) = JSONObject().put("revision", plan.revision).put("paused", plan.paused)
            .put("decisionCount", plan.decisions.size).put("steps", JSONArray(plan.steps.map { step -> JSONObject()
                .put("id", step.id).put("paths", JSONArray(step.paths)).put("dependsOn", JSONArray(step.dependsOn))
                .put("status", step.status.name).put("hasUserReportedCheckpoint", step.checkpoint.isNotBlank()) }))
        val json = JSONObject().put("schema", "jarvis-phase3-two-session-acceptance-v1")
            .put("taskId", completed.id).put("task", completed.task).put("baseCommitSha", completed.baseCommitSha)
            .put("analysisId", analysis.analysisId).put("scope", JSONArray(completed.scope))
            .put("sourceBlobs", JSONArray(sources.map { (path, bytes) -> JSONObject().put("path", path).put("gitBlobSha", gitBlob(bytes)).put("bytes", bytes.size) }))
            .put("readOnlyAnalysis", JSONObject().put("modeledGetCount", fixture.requests.size)
                .put("closedResponses", fixture.closedResponses).put("retrievedPaths", JSONArray(fixture.sourceReads)))
            .put("session1Saved", summary(saved)).put("session2Recovered", summary(recovered)).put("session2Completed", summary(completed))
            .put("savedCheckpointSha256", savedCheckpointHash).put("resumedCompletionOrder", JSONArray(completionOrder))
            .put("checks", JSONObject().put("queueEditsPreserved", true).put("scopeAndBasePreserved", true)
                .put("decisionHistoryPreserved", true).put("dependencyOrderRespected", true)
                .put("interruptedWorkBlockedAndPaused", true).put("approvalPersisted", false)
                .put("sourceOrDiffPersisted", false).put("repositoryPathsAndBytesUnchanged", true))
            .put("evidenceBoundaries", JSONArray(listOf(
                "Actual immutable checkout source bytes and computed Git blob SHA-1 identities; scoped modeled GET protocol, no network.",
                "Production TaskPlanner and binary TaskPlanStore exercised through independent local filesystem instances in a temporary directory.",
                "Step completion is a simulated user report; the planner did not execute source edits, compiler commands, tests or CI.",
                "A session-only exact-approved patch was not serialized; resumption grants no write approval or execution capability.",
                "No live provider, GitHub write, Android lifecycle/device verification, publication or installation is claimed."
            )))
        val directory = root.resolve("developer/build/reports/phase3-acceptance")
        Files.createDirectories(directory)
        Files.write(directory.resolve("two-session-evidence.json"), (json.toString(2) + "\n").toByteArray(Charsets.UTF_8))
    }

    /** A scoped GitHub GET fixture wraps two real files; its tree identity is explicitly modeled. */
    private class CheckoutGetFixture(private val root: Path, private val commit: String,
        private val sourceBytes: Map<String, ByteArray>) : RepositoryIntelligence.GetTransport {
        private val api = "https://api.github.com/repos/muddigger782012/J.A.R.V.I.S.-"
        private val treeSha = gitBlob(("MODELED SCOPED TREE\n" + sourceBytes.entries.joinToString("\n") {
            (path, bytes) -> "$path:${gitBlob(bytes)}"
        }).toByteArray(Charsets.UTF_8))
        private val metadataUrls = listOf("$api/git/ref/heads/feature%2Fjarvis-developer", "$api/git/commits/$commit", "$api/git/trees/$treeSha?recursive=1")
        val requests = mutableListOf<String>()
        val sourceReads = mutableListOf<String>()
        var closedResponses = 0
            private set

        override fun get(url: String, token: String): RepositoryIntelligence.GetResponse {
            assertTrue("Analysis uses no write credential", token.isBlank())
            requests += url
            assertTrue(requests.size <= RepositoryIntelligence.MAX_SESSION_REQUESTS)
            val json = when (url) {
                metadataUrls[0] -> JSONObject().put("ref", "refs/heads/feature/jarvis-developer").put("object", JSONObject().put("type", "commit").put("sha", commit))
                metadataUrls[1] -> JSONObject().put("sha", commit).put("tree", JSONObject().put("sha", treeSha))
                metadataUrls[2] -> JSONObject().put("sha", treeSha).put("truncated", false).put("tree", JSONArray(sourceBytes.map { (path, bytes) ->
                    JSONObject().put("path", path).put("type", "blob").put("mode", "100644").put("sha", gitBlob(bytes)).put("size", bytes.size)
                }))
                else -> {
                    assertTrue("Only pinned source GETs are permitted", url.startsWith("$api/contents/") && url.endsWith("?ref=$commit"))
                    val path = url.removePrefix("$api/contents/").substringBefore('?').split('/').joinToString("/") { URLDecoder.decode(it, "UTF-8") }
                    assertTrue("Source GET escaped the explicit analysis fixture", path in sourceBytes && RepositoryIntelligence.allowedSource(path))
                    val file = root.resolve(path).normalize()
                    assertTrue(file.startsWith(root) && Files.isRegularFile(file) && !Files.isSymbolicLink(file))
                    val bytes = Files.readAllBytes(file)
                    assertTrue("Checkout source changed during analysis", bytes.contentEquals(sourceBytes.getValue(path)))
                    sourceReads += path
                    JSONObject().put("path", path).put("type", "file").put("size", bytes.size).put("sha", gitBlob(bytes))
                        .put("encoding", "base64").put("content", Base64.getEncoder().encodeToString(bytes))
                }
            }
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            return RepositoryIntelligence.GetResponse(200, bytes.size.toLong(), url, ByteArrayInputStream(bytes)) { closedResponses++ }
        }
    }

    private fun snapshot(root: Path): Map<String, String> {
        val files = sortedMapOf<String, String>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (directory != root && directory.fileName.toString() in setOf(".git", ".gradle", ".kotlin", "build", "out", "node_modules"))
                    return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (attributes.isRegularFile && !Files.isSymbolicLink(file) && file.fileName.toString() != ".git")
                    files[root.relativize(file).toString().replace('\\', '/')] = gitBlob(Files.readAllBytes(file))
                return FileVisitResult.CONTINUE
            }
        })
        return files
    }

    companion object {
        private const val SOURCE = "developer/src/main/java/com/jarvis/developer/TaskFileSummary.kt"
        private const val TEST = "developer/src/test/java/com/jarvis/developer/TaskFileSummaryTest.kt"
        private const val TASK = "Review Kotlin, Java, XML and Gradle type counts in TaskFileSummary while preserving distinct sorted paths, duplicate handling and empty-scope summary tests."
        private fun gitBlob(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
            return digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
