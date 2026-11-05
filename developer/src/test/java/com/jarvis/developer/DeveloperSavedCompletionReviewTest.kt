package com.jarvis.developer

import android.app.Activity
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DeveloperSavedCompletionReviewTest {
    internal data class Fixture(
        val plans: File, val history: File, val store: TaskPlanStore,
        val bookmark: RepairHistoryStore.Bookmark, val report: BuildDiagnostics.Report,
        val activity: Activity
    )

    companion object {
        private val path = "developer/src/main/java/com/jarvis/developer/Latency.kt"
        private val base = "a".repeat(40)
        private val commit = "c".repeat(40)
        private val proposal = "b".repeat(64)

        internal fun createFixture(multi: Boolean = false): Fixture {
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val planDirectory = Files.createTempDirectory("saved-plan-review-").toFile()
            val historyDirectory = Files.createTempDirectory("saved-branch-review-").toFile()
            val store = TaskPlanStore(planDirectory)
            val second = "developer/src/main/java/com/jarvis/developer/Network.kt"
            val scope = if (multi) listOf(path, second) else listOf(path)
            val steps = mutableListOf(
                TaskPlanner.Step("analyze", "Analyze", "Review source", emptyList(), emptyList()),
                TaskPlanner.Step("implement-1", "Implement", "Scoped edit", listOf(path), listOf("analyze"))
            )
            if (multi) steps += TaskPlanner.Step("implement-2", "Implement network",
                "Scoped network edit", listOf(second), listOf("implement-1"))
            steps += TaskPlanner.Step("validate", "Validate", "Automated CI", emptyList(),
                if (multi) listOf("implement-1", "implement-2") else listOf("implement-1"))
            steps += TaskPlanner.Step("review", "Review", "Local final review",
                emptyList(), listOf("validate"))
            val initial = TaskPlanner.Plan("plan-saved-final", "Fix Developer latency", base,
                "d".repeat(64), scope, steps,
                listOf(TaskPlanner.Decision(1, null, "Created source plan", 1000L)), 0L, false, 1000L)
            store.save(initial, null)
            val accepted = TaskPlanner.transition(initial, "analyze", TaskPlanner.Status.DONE,
                "Owner reviewed and accepted analysis", 1001L)
            store.save(accepted, 0L)
            val id = requireNotNull(DeveloperPlanContinuation.suggest(listOf(accepted)).packet).id
            val committed = PatchPublisher.CommitResult(id, "Gemini", "gemini-2.5-pro",
                proposal, requireNotNull(DeveloperPlanContinuation.suggest(listOf(accepted)).packet).paths,
                base, commit,
                PatchEngine.taskBranch(id, proposal, PatchEngine.Target.DEVELOPER),
                "complete reviewed patch", "feature/jarvis-developer", PatchEngine.Target.DEVELOPER)
            val ci = PatchPublisher.CiStatus(PatchPublisher.CiState.PASSED,
                "https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/247", "success")
            assertNotNull(DeveloperPlanBuildCheckpoint.record(store, committed, ci, 1002L))
            val bookmark = RepairHistoryStore.bookmark(committed)
            RepairHistoryStore(historyDirectory).save(bookmark)
            val counts = JSONObject().put("tests", 1).put("failures", 0)
                .put("errors", 0).put("skipped", 0)
            val suite = "com.jarvis.developer.SavedReviewTest"
            val summary = JSONObject()
                .put("sourceCommit", commit).put("totals", counts)
                .put("suites", JSONArray().put(JSONObject(counts.toString())
                    .put("name", suite).put("sha256", "e".repeat(64))))
                .put("cases", JSONArray().put(JSONObject().put("suite", suite)
                    .put("name", "passedFixture").put("result", "passed")))
            val report = BuildDiagnostics.parse(commit, bookmark.branch, 247L, 1,
                "completed", "success", "JARVIS_JUNIT_SUMMARY=$summary")
            assertTrue("Complete JUnit evidence required", report.evidenceComplete)
            return Fixture(planDirectory, historyDirectory, store, bookmark, report, activity)
        }
    }

    private fun newReview(f: Fixture, now: () -> Long = { 1003L },
        read: (RepairHistoryStore.Bookmark) -> BuildDiagnostics.Report = { f.report }) =
        DeveloperSavedCompletionReview({ f.history }, { f.plans },
            { _, bookmark, cancellation ->
                cancellation.check()
                read(bookmark)
            }, now)

    @Test fun completeSavedCommitCanBeReviewedAfterRestartWithOneTimeExplicitConsent() {
        val f = createFixture()
        val review = newReview(f)
        val cancellation = PatchPreviewCancellation()
        assertTrue(review.confirm(f.activity, "test-key", cancellation).contains("No fresh saved"))
        val report = review.prepare(f.activity, "test-key", cancellation)
        assertTrue(report.text.contains("no approval restored"))
        assertTrue(report.text.contains(f.bookmark.branch))
        assertTrue(report.text.contains(f.report.runUrl))
        assertEquals(TaskPlanner.Status.PENDING, f.store.loadAll().single().steps.last().status)
        review.arm(report)
        assertTrue(review.confirm(f.activity, "test-key", cancellation).contains("LOCAL final review acknowledged"))
        val saved = f.store.loadAll().single()
        assertEquals(3L, saved.revision)
        assertEquals(TaskPlanner.Status.DONE, saved.steps.last().status)
        assertTrue(saved.steps.last().checkpoint.contains("no source write"))
        assertTrue(review.confirm(f.activity, "test-key", cancellation).contains("No fresh saved"))
    }

    @Test fun savedWholeScopeTwoStepCiSupportsRestartSafeChatReview() {
        val f = createFixture(multi = true)
        val ready = f.store.loadAll().single()
        assertEquals(4, ready.steps.count { it.status == TaskPlanner.Status.DONE })
        val review = newReview(f)
        val cancellation = PatchPreviewCancellation()
        val prepared = review.prepare(f.activity, "test-key", cancellation)
        assertEquals(2, prepared.plan.scope.size)
        assertTrue(prepared.text.contains("no approval restored"))
        review.arm(prepared)
        assertTrue(review.confirm(f.activity, "test-key", cancellation)
            .contains("LOCAL final review acknowledged"))
        assertEquals(TaskPlanner.Status.DONE, f.store.loadAll().single().steps.last().status)
    }

    @Test fun incompleteRunCannotClaimSavedTaskAcceptance() {
        val f = createFixture()
        val invalid = BuildDiagnostics.parse(f.bookmark.commitSha, f.bookmark.branch, 247L, 1,
            "completed", "success", "")
        assertFalse(invalid.evidenceComplete)
        val review = newReview(f, read = { invalid })
        assertThrows(IllegalArgumentException::class.java) {
            review.prepare(f.activity, "test-key", PatchPreviewCancellation())
        }
        assertEquals(TaskPlanner.Status.PENDING, f.store.loadAll().single().steps.last().status)
    }

    @Test fun changedBookmarkOrEditedPlanConsumesOldConsent() {
        val f = createFixture()
        val review = newReview(f)
        val cancellation = PatchPreviewCancellation()
        review.arm(review.prepare(f.activity, "test-key", cancellation))
        RepairHistoryStore(f.history).save(
            RepairHistoryStore.Bookmark(PatchEngine.Target.DEVELOPER,
                "feature/jarvis-developer-task/other-" + "f".repeat(12), "f".repeat(40)))
        assertTrue(review.confirm(f.activity, "test-key", cancellation).contains("Consent consumed"))
        assertEquals(TaskPlanner.Status.PENDING, f.store.loadAll().single().steps.last().status)
        // Restoring the original bookmark does not revive old consent.
        RepairHistoryStore(f.history).save(f.bookmark)
        review.arm(review.prepare(f.activity, "test-key", cancellation))
        val before = f.store.loadAll().single()
        f.store.save(TaskPlanner.note(before, "Source review risk changed", 1004L), before.revision)
        assertTrue(review.confirm(f.activity, "test-key", cancellation).contains("Consent consumed"))
        assertEquals(TaskPlanner.Status.PENDING, f.store.loadAll().single().steps.last().status)
    }

    @Test fun cancelledOrExpiredSavedReviewCannotWriteLocalCompletion() {
        val f = createFixture()
        var clock = 1003L
        val review = newReview(f, { clock })
        val cancellation = PatchPreviewCancellation()
        review.arm(review.prepare(f.activity, "test-key", cancellation))
        clock += 600_001L
        assertTrue(review.confirm(f.activity, "test-key", cancellation).contains("Consent consumed"))
        assertEquals(TaskPlanner.Status.PENDING, f.store.loadAll().single().steps.last().status)
        clock = 1003L
        review.arm(review.prepare(f.activity, "test-key", cancellation))
        cancellation.cancel()
        assertTrue(review.confirm(f.activity, "test-key", cancellation).contains("Consent consumed"))
        assertEquals(TaskPlanner.Status.PENDING, f.store.loadAll().single().steps.last().status)
    }
}
