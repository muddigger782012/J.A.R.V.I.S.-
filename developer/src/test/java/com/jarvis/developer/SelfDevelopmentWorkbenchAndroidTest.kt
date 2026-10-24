package com.jarvis.developer

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.ArrayDeque

/**
 * Android-widget and lifecycle integration with explicitly MODELED source, CI and APK facts.
 * Readers are instance fakes; queued work never opens a transport or uses credentials.
 * Real production parsers/evaluation/confirmation run behind actual button/text listeners.
 * This is JVM Android simulation, not an installed app, device check or real promotion approval.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class SelfDevelopmentWorkbenchAndroidTest {
    private val activities = mutableListOf<ActivityController<Activity>>()

    @After fun closeActivities() {
        activities.asReversed().forEach {
            if (!it.get().isDestroyed) it.pause().stop().destroy()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun attachingHasNoReaderCallsOrStagedApproval() {
        val harness = Harness()
        val screen = harness.screen()
        assertEquals(0, harness.reads)
        assertTrue(harness.work.isEmpty())
        assertTrue(harness.prepared.isEmpty())
        assertFalse(screen.button(STAGE).isEnabled)
        assertFalse(screen.button(COMPARE).isEnabled)
        assertFalse(screen.approval.isEnabled)
        assertFalse(screen.approval.isChecked)
        assertFalse(screen.button(CONFIRM).isEnabled)
        assertEquals("", screen.field(CONFIRMATION).text.toString())
        assertEquals(BASE_BRANCH, screen.field(BASE_BRANCH_HINT).text.toString())
    }

    @Test fun invalidSourceAndCiInputsRejectBeforeSchedulingAnyRead() {
        val harness = Harness()
        val screen = harness.screen()
        screen.field(SCOPE).setText("developer/src/main/java/com/jarvis/developer/MainActivity.kt")
        screen.button(ANALYZE).performClick()
        assertTrue(screen.status.text.contains("permitted existing own production paths"))
        screen.button(READ_BASELINE).performClick()
        assertTrue(screen.status.text.contains("exact lowercase source SHA"))
        screen.button(READ_SIGNED).performClick()
        assertTrue(screen.status.text.contains("completed evaluation run ID"))
        assertEquals(0, harness.reads)
        assertTrue(harness.work.isEmpty())
        assertTrue(harness.prepared.isEmpty())
    }

    @Test fun sourceAnalysisStagesOnlyItsExactPinnedScopeWithoutApproval() {
        val harness = Harness()
        val screen = harness.screen()
        screen.field(SCOPE).setText(SAFE_PATH)
        screen.button(ANALYZE).performClick()
        assertEquals(1, harness.work.size)
        assertFalse(screen.field(SCOPE).isEnabled)
        assertFalse(screen.button(STAGE).isEnabled)
        assertEquals(0, harness.reads)
        harness.finishWork()
        assertTrue(screen.button(STAGE).isEnabled)
        assertTrue(screen.field(SCOPE).isEnabled)
        screen.button(STAGE).performClick()
        val staged = harness.prepared.single()
        assertEquals(listOf(SAFE_PATH), staged.paths)
        assertEquals(BASE_SHA, staged.sha)
        assertEquals(BASE_BRANCH, staged.branch)
        assertTrue(staged.taskId.startsWith("self-"))
        assertTrue(staged.task.contains(GOAL))
        assertEquals(1, harness.reads)
        assertTrue(harness.work.isEmpty())
        assertFalse(screen.approval.isChecked)
        assertFalse(screen.button(CONFIRM).isEnabled)
        assertTrue(screen.status.text.contains("explicitly approve its new diff"))
    }

    @Test fun activePatchRestoresTextAndRefusesAnotherOperation() {
        val harness = Harness()
        val screen = harness.screen()
        screen.field(SCOPE).setText(SAFE_PATH)
        screen.field(BASE_SHA_HINT).setText(BASE_SHA)
        harness.patchBusy = true
        screen.field(BASE_SHA_HINT).setText("9".repeat(40))
        assertEquals(BASE_SHA, screen.field(BASE_SHA_HINT).text.toString())
        assertTrue(screen.status.text.contains("Finish the active operation"))
        screen.button(ANALYZE).performClick()
        assertTrue(screen.status.text.contains("Finish the active operation"))
        assertEquals(0, harness.reads)
        assertTrue(harness.work.isEmpty())
        assertTrue(harness.prepared.isEmpty())
    }

    @Test fun asyncReadFreezesInputsAndDestroyedActivityDropsItsResult() {
        val harness = Harness()
        val screen = harness.screen()
        screen.field(BASE_SHA_HINT).setText(BASE_SHA)
        screen.field(BASE_RUN).setText("71")
        screen.button(READ_BASELINE).performClick()
        assertFalse(screen.field(BASE_SHA_HINT).isEnabled)
        assertFalse(screen.button(READ_BASELINE).isEnabled)
        screen.field(BASE_SHA_HINT).setText("9".repeat(40))
        assertEquals(BASE_SHA, screen.field(BASE_SHA_HINT).text.toString())
        val outputBefore = screen.output.text.toString()
        val statusBefore = screen.status.text.toString()
        screen.controller.pause().stop().destroy()
        assertTrue(screen.activity.isDestroyed)
        harness.finishWork()
        assertEquals(1, harness.reads)
        assertEquals(outputBefore, screen.output.text.toString())
        assertEquals(statusBefore, screen.status.text.toString())
        assertFalse(screen.button(COMPARE).isEnabled)
        assertFalse(screen.approval.isEnabled)
    }

    @Test fun sourceMismatchedDebugResultCannotPopulateBaselineSlot() {
        val harness = Harness()
        val screen = harness.screen()
        screen.field(BASE_SHA_HINT).setText(BASE_SHA)
        screen.field(BASE_RUN).setText("71")
        harness.wrongDebugIdentity = true
        screen.button(READ_BASELINE).performClick()
        harness.finishWork()
        assertTrue(screen.status.text.contains("Feedback identity could not be verified"))
        assertTrue(screen.texts().any { it.contains("Baseline debug feedback: not read") })
        assertFalse(screen.button(COMPARE).isEnabled)
        assertFalse(screen.approval.isEnabled)
        assertEquals(1, harness.reads)
    }

    @Test fun typedDebugFailureReplacesOldEvidenceAndLeavesCandidateApprovalBlocked() {
        val harness = Harness()
        val screen = harness.screen()
        screen.field(BASE_SHA_HINT).setText(BASE_SHA)
        screen.field(BASE_RUN).setText("71")
        screen.button(READ_BASELINE).performClick(); harness.finishWork()
        harness.debugFailure = BuildFeedback.Failure(BuildFeedback.Stage.WORKFLOW_METADATA, BuildFeedback.Problem.TIMEOUT)
        screen.button(READ_BASELINE).performClick(); harness.finishWork()
        assertTrue(screen.output.text.contains("network request timed out"))
        assertTrue(screen.status.text.contains("Check connectivity"))
        assertTrue(screen.texts().any { it.contains("Baseline debug feedback: not read") })
        assertFalse(screen.button(COMPARE).isEnabled); assertFalse(screen.approval.isEnabled)
    }

    @Test fun readyEvidenceStillNeedsExactFingerprintAndExplicitMetadataConfirmation() {
        val harness = Harness()
        val screen = harness.screen()
        val fingerprint = harness.loadEligibleReview(screen)
        assertTrue(screen.approval.isEnabled)
        assertFalse(screen.approval.isChecked)
        assertFalse(screen.button(CONFIRM).isEnabled)

        screen.field(CONFIRMATION).setText("9".repeat(64))
        screen.approval.isChecked = true
        assertFalse(screen.button(CONFIRM).isEnabled)
        screen.field(CONFIRMATION).setText(fingerprint)
        assertFalse(screen.approval.isChecked)
        assertFalse(screen.button(CONFIRM).isEnabled)
        screen.approval.isChecked = true
        assertTrue(screen.button(CONFIRM).isEnabled)
        screen.button(CONFIRM).performClick()

        assertTrue(screen.output.text.startsWith("promotion-request-v1 METADATA ONLY"))
        assertTrue(screen.output.text.contains("This metadata object has no action authority"))
        assertTrue(screen.status.text.contains("No release, install, merge or workflow dispatch occurred"))
        assertFalse(screen.approval.isEnabled)
        assertFalse(screen.approval.isChecked)
        assertFalse(screen.button(CONFIRM).isEnabled)
        assertEquals(3, harness.reads)
        assertTrue(harness.prepared.isEmpty())
        assertTrue(harness.work.isEmpty())
    }

    @Test fun changingCandidateSourceClearsReviewEvidenceAndConfirmation() {
        val harness = Harness()
        val screen = harness.screen()
        screen.field(CONFIRMATION).setText(harness.loadEligibleReview(screen))
        screen.approval.isChecked = true
        assertTrue(screen.button(CONFIRM).isEnabled)
        screen.field(CANDIDATE_SHA_HINT).setText("9".repeat(40))
        assertEquals("", screen.field(CONFIRMATION).text.toString())
        assertFalse(screen.approval.isChecked)
        assertFalse(screen.approval.isEnabled)
        assertFalse(screen.button(CONFIRM).isEnabled)
        assertFalse(screen.button(COMPARE).isEnabled)
        assertTrue(screen.texts().any { it.contains("Candidate debug feedback: not read") })
        assertTrue(screen.texts().any { it.contains("Signed candidate/recovery evidence: not read") })
        assertEquals(3, harness.reads)
        assertTrue(harness.prepared.isEmpty())
    }

    @Test fun expiredConfirmationRemovesReviewAndRequiresFreshEvaluation() {
        val harness = Harness()
        val screen = harness.screen()
        val fingerprint = harness.loadEligibleReview(screen)
        screen.field(CONFIRMATION).setText(fingerprint)
        screen.approval.isChecked = true
        harness.now += CandidateEvaluation.REVIEW_DURATION_MILLIS
        screen.button(CONFIRM).performClick()
        assertTrue(screen.status.text.contains("confirmation refused or expired"))
        assertFalse(screen.button(CONFIRM).isEnabled)
        assertFalse(screen.approval.isEnabled)
        assertFalse(screen.output.text.startsWith("promotion-request-v1"))
        screen.button(COMPARE).performClick()
        assertTrue(screen.approval.isEnabled)
        assertFalse(screen.approval.isChecked)
        assertEquals("", screen.field(CONFIRMATION).text.toString())
        assertNotEquals(fingerprint, screen.reviewFingerprint())
        assertEquals(3, harness.reads)
    }

    @Test fun replacingDestroyedScreenRestoresNoEvidenceOrApproval() {
        val harness = Harness()
        val first = harness.screen()
        first.field(CONFIRMATION).setText(harness.loadEligibleReview(first))
        first.approval.isChecked = true
        assertTrue(first.button(CONFIRM).isEnabled)
        first.controller.pause().stop().destroy()
        val replacement = harness.screen()
        assertEquals("", replacement.field(BASE_SHA_HINT).text.toString())
        assertEquals("", replacement.field(CANDIDATE_SHA_HINT).text.toString())
        assertEquals("", replacement.field(CONFIRMATION).text.toString())
        assertFalse(replacement.button(COMPARE).isEnabled)
        assertFalse(replacement.approval.isChecked)
        assertFalse(replacement.approval.isEnabled)
        assertFalse(replacement.button(CONFIRM).isEnabled)
        assertTrue(replacement.texts().any { it.contains("Baseline debug feedback: not read") })
        assertEquals(3, harness.reads)
        assertTrue(harness.work.isEmpty())
        assertTrue(harness.prepared.isEmpty())
    }

    private data class Staged(val taskId: String, val task: String, val paths: List<String>, val sha: String, val branch: String)

    private inner class Harness {
        val work = ArrayDeque<() -> Unit>()
        val prepared = mutableListOf<Staged>()
        var reads = 0
        var patchBusy = false
        var wrongDebugIdentity = false
        var debugFailure: BuildFeedback.Failure? = null
        var now = 1_000L
        private val services = SelfDevelopmentWorkbench.Services(
            analyze = { token, goal ->
                assertEquals("", token)
                assertEquals(GOAL, goal)
                reads++
                modeledAnalysis(goal)
            },
            readDebug = { token, branch, sha, run ->
                assertEquals("", token)
                reads++
                debugFailure?.let { throw it }
                if (wrongDebugIdentity) modeledReport(CANDIDATE_SHA, TASK_BRANCH, 72L, true)
                else {
                    assertEquals(if (sha == BASE_SHA) BASE_BRANCH else TASK_BRANCH, branch)
                    assertEquals(if (sha == BASE_SHA) 71L else 72L, run)
                    modeledReport(sha, branch, requireNotNull(run), sha == CANDIDATE_SHA)
                }
            },
            readCandidate = { token, branch, sha, run, controller ->
                assertEquals("", token)
                assertEquals(TASK_BRANCH, branch)
                assertEquals(CANDIDATE_SHA, sha)
                assertEquals(73L, run)
                assertEquals(BASE_SHA, controller)
                reads++
                modeledEvidence()
            },
            worker = { _, operation -> work.addLast(operation); Unit },
            nowMillis = { now }
        )

        fun screen(): Screen {
            val controller = Robolectric.buildActivity(Activity::class.java)
            controller.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            controller.setup()
            activities.add(controller)
            val screen = Screen(controller)
            val task = EditText(screen.activity).apply { setText(GOAL) }
            val token = EditText(screen.activity)
            val patches = PatchWorkbench.Handle(
                prepareAction = { id, goal, paths, sha, branch ->
                    prepared.add(Staged(id, goal, paths, sha, branch)); true
                },
                repairAction = { _, _ -> error("Self-development must never reserve a repair") },
                invalidateAction = { !patchBusy }
            )
            SelfDevelopmentWorkbench.attach(screen.activity, screen.root, task, token,
                screen.output, screen.status, patches, services)
            return screen
        }

        fun finishWork() {
            assertEquals("Each explicit read schedules exactly one worker", 1, work.size)
            work.removeFirst().invoke()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(work.isEmpty())
        }

        fun loadEligibleReview(screen: Screen): String {
            screen.field(BASE_SHA_HINT).setText(BASE_SHA)
            screen.field(BASE_RUN).setText("71")
            screen.field(CANDIDATE_BRANCH).setText(TASK_BRANCH)
            screen.field(CANDIDATE_SHA_HINT).setText(CANDIDATE_SHA)
            screen.field(CANDIDATE_RUN).setText("72")
            screen.field(CONTROLLER_SHA).setText(BASE_SHA)
            screen.field(SIGNED_RUN).setText("73")
            listOf(READ_BASELINE, READ_CANDIDATE, READ_SIGNED).forEach {
                screen.button(it).performClick()
                finishWork()
            }
            assertTrue(screen.button(COMPARE).isEnabled)
            screen.button(COMPARE).performClick()
            assertTrue(screen.output.text.toString(), screen.output.text.startsWith("Candidate eligible for a promotion request"))
            return screen.reviewFingerprint()
        }
    }

    private class Screen(val controller: ActivityController<Activity>) {
        val activity: Activity get() = controller.get()
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val output = TextView(activity).apply { text = "Unchanged output" }
        val status = TextView(activity)
        init {
            root.addView(output)
            root.addView(status)
            activity.setContentView(root)
        }
        fun field(hint: String): EditText = descendants(root).filterIsInstance<EditText>().single { it.hint.toString() == hint }
        fun button(label: String): Button = descendants(root).filterIsInstance<Button>().single { it.text.toString() == label }
        val approval: CheckBox get() = descendants(root).filterIsInstance<CheckBox>().single()
        fun texts(): List<String> = descendants(root).filterIsInstance<TextView>().map { it.text.toString() }
        fun reviewFingerprint(): String = requireNotNull(Regex("(?m)^Review: ([a-f0-9]{64})$").find(output.text)) {
            "The production review must render an exact fingerprint"
        }.groupValues[1]
    }

    private fun modeledAnalysis(goal: String): RepositoryAnalysis.AnalysisPlan {
        val file = RepositoryAnalysis.PlannedFile(RepositoryAnalysis.SourceFile(SAFE_PATH, "d".repeat(40), 120L),
            listOf("Modeled source reason"), "Modeled change", "Modeled impact",
            listOf(RepositoryAnalysis.Symbol("summarize", "fun", 3)), emptyList(),
            listOf(RepositoryAnalysis.Citation(SAFE_PATH, "d".repeat(40), BASE_SHA, 1, 6, "Modeled excerpt")))
        return RepositoryAnalysis.AnalysisPlan(goal, BASE_SHA, listOf(file), emptyList(), listOf(SAFE_PATH), 1)
    }

    private fun modeledReport(sha: String, branch: String, run: Long, addedCase: Boolean): BuildDiagnostics.Report {
        val suite = "com.jarvis.developer.TaskFileSummaryTest"
        val cases = JSONArray().put(JSONObject().put("suite", suite).put("name", "existingSummary").put("result", "passed"))
        if (addedCase) cases.put(JSONObject().put("suite", suite).put("name", "newClassification").put("result", "passed"))
        val counts = JSONObject().put("tests", cases.length()).put("failures", 0).put("errors", 0).put("skipped", 0)
        val summary = JSONObject().put("sourceCommit", sha).put("totals", counts)
            .put("suites", JSONArray().put(JSONObject(counts.toString()).put("name", suite).put("sha256", "4".repeat(64))))
            .put("cases", cases)
        return BuildDiagnostics.parse(sha, branch, run, 1, "completed", "success", "JARVIS_JUNIT_SUMMARY=$summary")
    }

    private fun modeledEvidence(): CandidateEvaluation.Evidence {
        fun artifact(version: Int, hash: Char) = JSONObject().put("sourceCommitSha", "c".repeat(40))
            .put("packageName", CandidateEvaluation.PACKAGE_NAME).put("versionCode", version)
            .put("apkSha256", hash.toString().repeat(64)).put("apkBytes", 2048).put("signerSha256", "e".repeat(64))
        val json = JSONObject().put("schemaVersion", 1).put("status", "READY")
            .put("candidate", JSONObject().put("commitSha", CANDIDATE_SHA).put("branch", TASK_BRANCH)
                .put("controllerCommitSha", BASE_SHA).put("controllerBranch", BASE_BRANCH).put("runId", 73).put("runAttempt", 1)
                .put("packageName", CandidateEvaluation.PACKAGE_NAME).put("versionCode", 101)
                .put("apkSha256", "2".repeat(64)).put("apkBytes", 2300).put("signerSha256", "e".repeat(64)))
            .put("tests", JSONObject().put("passed", true).put("tests", 2).put("failures", 0).put("errors", 0)
                .put("skipped", 0).put("lintErrors", 0).put("lintWarnings", 0))
            .put("source", JSONObject().put("baseCommitSha", BASE_SHA).put("changedPaths", JSONArray().put(SAFE_PATH))
                .put("scopeVerified", true).put("controlsUnchanged", true).put("branchIsolated", true))
            .put("recovery", JSONObject().put("baseline", artifact(100, '1'))
                .put("backup", artifact(100, '1').put("verified", true))
                .put("rebuild", artifact(102, '3').put("verified", true)).put("missingReasons", JSONArray()))
        return CandidateEvaluation.parseEvidence(json.toString(), CandidateEvaluation.Expected(CANDIDATE_SHA, TASK_BRANCH, BASE_SHA, 73L, 1))
    }

    companion object {
        private val BASE_SHA = "a".repeat(40)
        private val CANDIDATE_SHA = "b".repeat(40)
        private const val BASE_BRANCH = "feature/jarvis-developer"
        private const val TASK_BRANCH = "feature/jarvis-developer-task/widget-review-dddddddddddd"
        private const val SAFE_PATH = "developer/src/main/java/com/jarvis/developer/TaskFileSummary.kt"
        private const val GOAL = "Refine summary classification"
        private const val SCOPE = "1–8 existing permitted Developer production paths, one per line"
        private const val BASE_BRANCH_HINT = "Successful baseline branch"
        private const val BASE_SHA_HINT = "Successful baseline exact source SHA"
        private const val BASE_RUN = "Optional baseline debug workflow run ID"
        private const val CANDIDATE_BRANCH = "Isolated candidate task branch"
        private const val CANDIDATE_SHA_HINT = "Candidate exact source SHA"
        private const val CANDIDATE_RUN = "Optional candidate debug workflow run ID"
        private const val CONTROLLER_SHA = "Trusted evaluation controller exact source SHA"
        private const val SIGNED_RUN = "Completed candidate evaluation workflow run ID"
        private const val CONFIRMATION = "Enter exact eligible review SHA-256"
        private const val ANALYZE = "Read source and propose bounded own-code improvements (GET only)"
        private const val STAGE = "Stage this exact source scope for a NEW diff review"
        private const val READ_BASELINE = "Read exact baseline debug CI (GET only)"
        private const val READ_CANDIDATE = "Read exact candidate debug CI (GET only)"
        private const val READ_SIGNED = "Read signed candidate and recovery evidence (GET only)"
        private const val COMPARE = "Compare baseline, candidate and verified recovery evidence"
        private const val CONFIRM = "Record exact approved promotion request (metadata only)"

        private fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    }
}
