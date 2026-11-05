package com.jarvis.developer

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import java.security.MessageDigest
import java.util.ArrayDeque
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercises the real review widgets with deterministic repository/CI fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PatchWorkbenchDeveloperRepairAndroidTest {
    private val canonical = "feature/jarvis-developer"
    private val sourceSha = "a".repeat(40)
    private val failedCommit = "c".repeat(40)
    private val repairedCommit = "e".repeat(40)
    private val path = "developer/src/main/java/com/jarvis/developer/VoiceLatency.kt"
    private val before = "package com.jarvis.developer\nobject VoiceLatency { fun delay() = 10 }\n"
    private val firstEdit = "package com.jarvis.developer\nobject VoiceLatency { fun delay() = 5 }\n"
    private val repaired = "package com.jarvis.developer\nobject VoiceLatency { fun delay() = 4 }\n"
    private fun blob(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-1").digest(
            ("blob ${bytes.size}\u0000").toByteArray(Charsets.UTF_8) + bytes
        ).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private inner class Screen(val infrastructureFailure: Boolean = false,
        val historyDirectory: java.io.File = java.nio.file.Files.createTempDirectory("developer-bookmark-").toFile(),
        val autoRepairPreview: Boolean = false,
        val successfulRepairBuild: Boolean = false) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val prompt = EditText(activity).apply { setText("Improve the voice response delay") }
        val key = EditText(activity).apply { setText("simulated-provider-key") }
        val token = EditText(activity).apply { setText("simulated-github-token") }
        val model = EditText(activity).apply { setText("gpt-4.1-mini") }
        val provider = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, listOf("OpenAI"))
        }
        val output = TextView(activity)
        val status = TextView(activity)
        val queued = ArrayDeque<() -> Unit>()
        var taskBranch: String? = null
        var providerCalls = 0
        var writes = 0
        var ciReads = 0
        var planEvidenceCalls = 0
        var ciState = PatchPublisher.CiState.FAILED
        val ciTransitions = ArrayDeque<PatchPublisher.CiState>()
        val chatUpdates = mutableListOf<String>()
        var logReads = 0
        var movedTaskSha = false
        val handle: PatchWorkbench.Handle
        init {
            activity.setContentView(root)
            handle = PatchWorkbench.attach(activity, root, prompt, key, provider, model, token,
                output, status, { true }, PatchWorkbench.Services(
                    inventory = { _, branch, target, cancellation ->
                        cancellation.check()
                        assertEquals(PatchEngine.Target.DEVELOPER, target)
                        val content = when (branch) {
                            canonical -> before
                            taskBranch -> firstEdit
                            else -> error("Unexpected source branch: $branch")
                        }
                        val sha = if (branch == canonical) sourceSha else
                            if (movedTaskSha) "d".repeat(40) else failedCommit
                        RepositoryAnalysis.Inventory(listOf(RepositoryAnalysis.SourceFile(path,
                            blob(content), content.toByteArray(Charsets.UTF_8).size.toLong())),
                            false, sha)
                    },
                    readSource = { _, inventory, selected, cancellation ->
                        cancellation.check(); assertEquals(path, selected)
                        if (inventory.commitSha == sourceSha) before else firstEdit
                    },
                    propose = { _, request, cancellation ->
                        cancellation.check(); providerCalls++
                        assertEquals(taskBranch, request.baseBranch)
                        assertEquals(failedCommit, request.baseCommitSha)
                        assertEquals(listOf(path), request.allowedPaths)
                        PatchEngine.parse(request, JSONObject()
                            .put("summary", "New isolated compiler repair")
                            .put("edits", JSONArray().put(JSONObject()
                                .put("path", path).put("content", repaired))).toString())
                    },
                    commit = { _, approved ->
                        PatchEngine.validateApproval(approved)
                        writes++
                        val proposal = approved.proposal
                        val req = proposal.request
                        taskBranch = PatchEngine.branchName(proposal)
                        PatchPublisher.CommitResult(req.taskId, req.provider, req.model,
                            proposal.id, proposal.edits.map { it.path }.sorted(),
                            req.baseCommitSha,
                            if (successfulRepairBuild && writes == 2) repairedCommit else failedCommit,
                            requireNotNull(taskBranch),
                            proposal.diff, req.baseBranch, req.target)
                    },
                    ci = { _, result ->
                        ciReads++
                        assertTrue(result.commitSha == failedCommit ||
                            (successfulRepairBuild && result.commitSha == repairedCommit))
                        val state = if (result.commitSha == repairedCommit && successfulRepairBuild)
                            PatchPublisher.CiState.PASSED
                        else if (ciTransitions.isEmpty()) ciState else ciTransitions.removeFirst()
                        PatchPublisher.CiStatus(state,
                            if (state == PatchPublisher.CiState.MISSING) null else
                                "https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/23",
                            if (state == PatchPublisher.CiState.FAILED) "failure" else
                                if (state == PatchPublisher.CiState.PASSED) "success" else null)
                    },
                    ciWatchPause = { millis -> assertEquals(15_000L, millis) },
                    autoRepairPreview = autoRepairPreview,
                    planCheckpoint = { _, result, checked ->
                        assertTrue(result.commitSha == failedCommit ||
                            (successfulRepairBuild && result.commitSha == repairedCommit))
                        assertEquals(PatchPublisher.CiState.PASSED, checked.state)
                        planEvidenceCalls++
                        "Evidence recorder acknowledged exact task CI"
                    },
                    feedback = { _, result, cancellation ->
                        cancellation.check(); logReads++
                        BuildDiagnostics.parse(result.commitSha, result.branch, 23, 1,
                            "completed", "failure",
                            if (infrastructureFailure) "Could not GET dependency: HTTP 503\n"
                            else "e: $path:2:1 Unresolved reference: untrusted-contents\n",
                            result.target)
                    },
                    worker = { queued.add(it) },
                    history = RepairHistoryWorkbench.Services(
                        directory = { historyDirectory },
                        feedback = { _, bookmark, cancellation ->
                            cancellation.check()
                            BuildDiagnostics.parse(bookmark.commitSha, bookmark.branch, 77, 1,
                                "completed", "success", "", bookmark.target)
                        }),
                    chatStatus = { chatUpdates.add(it) }
                ))
            shadowOf(Looper.getMainLooper()).idle()
        }
        private fun views(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
            else emptyList()
        fun button(label: String) = views(root).filterIsInstance<Button>()
            .single { it.text.toString().startsWith(label) }
        fun approval() = views(root).filterIsInstance<CheckBox>().single()
        fun finish() { queued.removeFirst().invoke(); shadowOf(Looper.getMainLooper()).idle() }
        fun initialFailure(expectedCiReads: Int = 1) {
            assertTrue(handle.importChatProposal("Improve the voice response delay", "Reduce delay",
                listOf(PatchEngine.Edit(path, firstEdit)), sourceSha))
            finish()
            assertTrue(approval().isEnabled)
            approval().isChecked = true
            button("Create task branch").performClick()
            finish()
            assertEquals(1, writes)
            assertEquals(expectedCiReads, ciReads)
            assertEquals(if (ciState == PatchPublisher.CiState.FAILED) 1 else 0, logReads)
            assertFalse(approval().isEnabled)
        }
        fun close() { handle.dispose(); controller.pause().stop().destroy() }
    }
    @Test fun verifiedCompilerFailureCanCompleteFreshOwnerApprovedRepairAndGreenSecondBuild() {
        val s = Screen(autoRepairPreview = true, successfulRepairBuild = true)
        try {
            // First approved exact-source commit fails CI. Developer reads
            // matching compiler diagnostics, then automatically stages a
            // NEW repair preview on that failed commit (not on canonical).
            s.initialFailure()
            assertEquals(1, s.writes)
            assertEquals(1, s.logReads)
            assertEquals(0, s.providerCalls)
            assertEquals(1, s.queued.size)
            assertFalse(s.approval().isChecked)
            s.finish()
            assertEquals(1, s.providerCalls)
            assertEquals(1, s.writes)
            assertTrue(s.output.text.contains("Scoped preview ready"))
            assertTrue(s.queued.isEmpty())

            // A fresh repair is NOT authorized until the owner reviews the
            // exact complete diff and consents with its verified fingerprint.
            assertFalse(s.handle.approveDeveloperDiff(DeveloperDiffApprovalCommand.REVIEWED))
            val review = requireNotNull(s.handle.showDeveloperDiff())
            assertTrue(review.contains("Complete reviewable diff"))
            assertTrue(review.contains(repaired.trim()))
            assertEquals(1, s.writes)
            assertTrue(s.handle.approveDeveloperDiff(DeveloperDiffApprovalCommand.REVIEWED))
            assertEquals(1, s.queued.size)
            assertFalse(s.approval().isEnabled)
            s.finish()

            // Second isolated commit passes CI. The cycle must not spawn
            // another repair, silently retry the write, or reuse consent.
            assertEquals(2, s.writes)
            assertEquals(1, s.providerCalls)
            assertEquals(2, s.ciReads)
            assertEquals(1, s.logReads)
            assertEquals(1, s.planEvidenceCalls)
            assertTrue(s.queued.isEmpty())
            assertTrue(s.chatUpdates.any { it.contains("CI: passed") })
            assertTrue(s.status.text.contains("CI passed"))
            assertFalse(s.handle.approveDeveloperDiff(DeveloperDiffApprovalCommand.REVIEWED))
            assertFalse(s.handle.prepareDeveloperRepair())
            assertFalse(s.approval().isEnabled)
        } finally { s.close() }
    }

    @Test fun compilerFailureStartsFreshRepairPreviewAutomaticallyButNeverWritesAgain() {
        val s = Screen(autoRepairPreview = true)
        try {
            s.initialFailure()
            // Exact task failure and compiler location were checked once before
            // a new source-scoped preview is queued. No second manual command.
            assertEquals(1, s.writes)
            assertEquals(1, s.logReads)
            assertEquals(0, s.providerCalls)
            assertEquals(1, s.queued.size)
            assertTrue(s.chatUpdates.any { it.contains("preview started automatically") })
            assertFalse(s.approval().isChecked)
            assertFalse(s.button("Create task branch").isEnabled)
            s.finish()
            assertEquals(1, s.providerCalls)
            assertEquals(1, s.writes)
            assertEquals(1, s.ciReads)
            assertTrue(s.output.text.contains("Scoped preview ready"))
            assertTrue(s.approval().isEnabled)
            assertFalse(s.approval().isChecked)
            assertFalse(s.button("Create task branch").isEnabled)
            assertFalse(s.handle.approveDeveloperDiff(DeveloperDiffApprovalCommand.REVIEWED))
        } finally { s.close() }
    }

    @Test fun lateExactCiRefreshCanPrepareFreshRepairAfterWatchBudgetExpires() {
        val s = Screen(autoRepairPreview = true)
        try {
            s.ciState = PatchPublisher.CiState.RUNNING
            s.initialFailure(expectedCiReads = DeveloperCiWatch.MAX_READS)
            assertEquals(0, s.providerCalls)
            assertEquals(0, s.logReads)
            assertEquals(0, s.queued.size)
            assertEquals(1, s.writes)
            // The workflow finishes after bounded monitoring. Its later
            // exact-source refresh is still a read-only operation.
            s.ciState = PatchPublisher.CiState.FAILED
            assertTrue(s.handle.refreshDeveloperCi())
            assertEquals(1, s.queued.size)
            s.finish()
            assertEquals(1, s.logReads)
            assertEquals(1, s.queued.size) // NEW preview, not another commit.
            assertEquals(0, s.providerCalls)
            assertEquals(1, s.writes)
            assertTrue(s.chatUpdates.any { it.contains("preview started automatically") })
            s.finish()
            assertEquals(1, s.providerCalls)
            assertEquals(1, s.writes)
            assertTrue(s.approval().isEnabled)
            assertFalse(s.approval().isChecked)
        } finally { s.close() }
    }

    @Test fun automaticRepairNeverStartsFromInfrastructureFailure() {
        val s = Screen(infrastructureFailure = true, autoRepairPreview = true)
        try {
            s.initialFailure()
            assertEquals(1, s.writes)
            assertEquals(1, s.logReads)
            assertEquals(0, s.providerCalls)
            assertEquals(0, s.queued.size)
            assertFalse(s.button("Prepare Developer repair").isEnabled)
            assertFalse(s.chatUpdates.any { it.contains("preview started automatically") })
        } finally { s.close() }
    }

    @Test fun automaticRepairNeverStartsOnPassingCI() {
        val s = Screen(autoRepairPreview = true)
        try {
            s.ciState = PatchPublisher.CiState.PASSED
            s.initialFailure()
            assertEquals(0, s.logReads)
            assertEquals(0, s.providerCalls)
            assertEquals(1, s.writes)
            assertEquals(0, s.queued.size)
            assertFalse(s.chatUpdates.any { it.contains("preview started automatically") })
        } finally { s.close() }
    }

    @Test fun exactChatApprovalConsumesOnlyItsOwnVerifiedDiffOnce() {
        val s = Screen()
        try {
            assertTrue(s.handle.importChatProposal("Improve the voice response delay", "Reduce delay",
                listOf(PatchEngine.Edit(path, firstEdit)), sourceSha))
            s.finish()
            val prefix = DeveloperDiffApprovalCommand.REVIEWED
            assertTrue(s.chatUpdates.last().contains("Approve Developer diff"))
            assertFalse(s.handle.approveDeveloperDiff("0000000000000000"))
            assertFalse(s.handle.approveDeveloperDiff(prefix))
            assertFalse(s.approval().isChecked)
            val reviewedDiff = s.handle.showDeveloperDiff()
            assertNotNull(reviewedDiff)
            assertTrue(reviewedDiff!!.contains("Complete reviewable diff"))
            assertEquals(0, s.writes)
            assertTrue(s.handle.approveDeveloperDiff(prefix))
            assertFalse(s.approval().isChecked)
            assertEquals(1, s.queued.size)
            s.finish()
            assertEquals(1, s.writes)
            assertEquals(1, s.ciReads)
            assertFalse(s.handle.approveDeveloperDiff(prefix))
            assertEquals(1, s.writes)
        } finally { s.close() }
    }

    @Test fun changedCodeTaskRevokesUnusedChatFingerprint() {
        val s = Screen()
        try {
            assertTrue(s.handle.importChatProposal("Improve the voice response delay", "Reduce delay",
                listOf(PatchEngine.Edit(path, firstEdit)), sourceSha))
            s.finish()
            val code = DeveloperDiffApprovalCommand.REVIEWED
            assertNotNull(s.handle.showDeveloperDiff())
            s.prompt.setText("Changed source task")
            assertFalse(s.handle.approveDeveloperDiff(code))
            assertEquals(0, s.writes)
            assertTrue(s.queued.isEmpty())
        } finally { s.close() }
    }

    @Test fun savedDeveloperTaskCanBeReadAfterActivityRecreationWithoutRevivingWritePermissions() {
        val directory = java.nio.file.Files.createTempDirectory("developer-shared-bookmark-").toFile()
        val first = Screen(historyDirectory = directory)
        try {
            first.initialFailure()
            assertTrue(first.handle.saveDeveloperBookmark())
            assertEquals(1, first.queued.size)
            first.finish()
            assertTrue(first.chatUpdates.last().contains("saved privately"))
            assertEquals(1, first.writes)
        } finally { first.close() }
        val reopened = Screen(historyDirectory = directory)
        try {
            assertTrue(reopened.handle.readSavedDeveloperBuild())
            assertEquals(1, reopened.queued.size)
            reopened.finish()
            assertTrue(reopened.chatUpdates.last().contains("Saved Developer build"))
            assertTrue(reopened.chatUpdates.last().contains("CI: success"))
            assertFalse(reopened.chatUpdates.last().contains("installed"))
            assertEquals(0, reopened.writes)
            assertEquals(0, reopened.providerCalls)
            assertFalse(reopened.approval().isEnabled)
            assertFalse(reopened.handle.refreshDeveloperCi())
            assertFalse(reopened.handle.prepareDeveloperRepair())
        } finally { reopened.close() }
    }

    @Test fun unsavedDeveloperHistoryCannotBeMistakenForSuccessfulBuild() {
        val s = Screen()
        try {
            assertTrue(s.handle.readSavedDeveloperBuild())
            s.finish()
            assertTrue(s.chatUpdates.last().contains("No explicitly saved"))
            assertEquals(0, s.writes)
            assertEquals(0, s.providerCalls)
        } finally { s.close() }
    }

    @Test fun queuedOrMissingTaskCIFollowsAutomaticallyWithoutSecondWriteOrProviderCall() {
        val s = Screen()
        try {
            s.ciState = PatchPublisher.CiState.MISSING
            s.ciTransitions.add(PatchPublisher.CiState.MISSING)
            s.ciTransitions.add(PatchPublisher.CiState.PASSED)
            // One owner-approved write, then two exact-commit CI reads without an extra chat command.
            s.initialFailure(expectedCiReads = 2)
            assertEquals(0, s.logReads)
            assertEquals(2, s.ciReads)
            assertEquals(1, s.planEvidenceCalls)
            assertTrue(s.chatUpdates.last().contains("CI: passed"))
            assertEquals(0, s.providerCalls)
            assertEquals(1, s.writes)
            assertFalse(s.approval().isEnabled)
            assertFalse(s.button("Prepare Developer repair").isEnabled)
            // Read-only refresh remains available but never repeats the source write.
            assertTrue(s.handle.refreshDeveloperCi())
            assertEquals(1, s.queued.size)
            s.finish()
            assertEquals(3, s.ciReads)
            assertEquals(1, s.writes)
        } finally { s.close() }
    }

    @Test fun verifiedGreenCiReportsOnePlanEvidenceEventWithoutExtraWrites() {
        val s = Screen()
        try {
            s.ciState = PatchPublisher.CiState.PASSED
            s.initialFailure()
            assertEquals(1, s.planEvidenceCalls)
            assertTrue(s.chatUpdates.last().contains("Evidence recorder acknowledged"))
            assertEquals(1, s.writes)
            assertEquals(0, s.providerCalls)
            assertTrue(s.handle.refreshDeveloperCi())
            s.finish()
            assertEquals(2, s.planEvidenceCalls)
            assertEquals(1, s.writes)
            assertEquals(0, s.providerCalls)
        } finally { s.close() }
    }

    @Test fun failedCiDoesNotRecordVerifiedImplementationCheckpoint() {
        val s = Screen()
        try {
            s.initialFailure()
            assertEquals(0, s.planEvidenceCalls)
            assertEquals(1, s.writes)
            assertEquals(1, s.logReads)
        } finally { s.close() }
    }

    @Test fun failedCIRefreshReturnsVerifiedDiagnosticEvidenceNotAnotherWrite() {
        val s = Screen()
        try {
            s.initialFailure()
            assertTrue(s.handle.refreshDeveloperCi())
            s.finish()
            assertEquals(2, s.ciReads)
            assertEquals(2, s.logReads)
            assertEquals(1, s.writes)
            assertEquals(0, s.providerCalls)
            assertTrue(s.button("Prepare Developer repair").isEnabled)
            assertTrue(s.output.text.contains("Verified exact-run diagnostics"))
        } finally { s.close() }
    }

    @Test fun explicitChatRouteUsesTheSameApprovalGatedDiffAsTheInlineButton() {
        val s = Screen()
        try {
            assertFalse(s.handle.prepareDeveloperRepair())
            s.initialFailure()
            assertTrue(s.handle.prepareDeveloperRepair())
            assertEquals(1, s.queued.size)
            s.finish()
            assertEquals(1, s.providerCalls)
            assertEquals(1, s.writes)
            assertTrue(s.approval().isEnabled)
            assertFalse(s.approval().isChecked)
            assertFalse(s.button("Create task branch").isEnabled)
        } finally { s.close() }
    }

    @Test fun confirmedCompilerFailureRequiresNewUserActionThenBuildsNewDiffWithoutAnotherWrite() {
        val s = Screen()
        try {
            s.initialFailure()
            assertTrue(s.button("Prepare Developer repair").isEnabled)
            assertEquals(0, s.providerCalls)
            s.button("Prepare Developer repair").performClick()
            assertEquals(1, s.queued.size)
            assertEquals(0, s.providerCalls)
            assertFalse(s.approval().isEnabled)
            s.finish()
            assertEquals(1, s.providerCalls)
            assertEquals(1, s.writes)
            assertTrue(s.approval().isEnabled)
            assertFalse(s.approval().isChecked)
            assertFalse(s.button("Create task branch").isEnabled)
            assertTrue(s.output.text.contains("Scoped preview ready"))
            assertFalse(s.button("Prepare Developer repair").isEnabled)
        } finally { s.close() }
    }

    @Test fun infrastructureFailureNeverOffersCompilerRepair() {
        val s = Screen(infrastructureFailure = true)
        try {
            s.initialFailure()
            assertFalse(s.button("Prepare Developer repair").isEnabled)
            s.button("Prepare Developer repair").performClick()
            assertEquals(0, s.providerCalls)
            assertEquals(1, s.writes)
            assertTrue(s.queued.isEmpty())
        } finally { s.close() }
    }

    @Test fun movedFailedTaskBranchRefusesRepairBeforeCallingProvider() {
        val s = Screen()
        try {
            s.initialFailure()
            s.movedTaskSha = true
            s.button("Prepare Developer repair").performClick()
            assertEquals(1, s.queued.size)
            s.finish()
            assertEquals(0, s.providerCalls)
            assertEquals(1, s.writes)
            assertFalse(s.approval().isEnabled)
            assertTrue(s.status.text.contains("unavailable"))
        } finally { s.close() }
    }
}
