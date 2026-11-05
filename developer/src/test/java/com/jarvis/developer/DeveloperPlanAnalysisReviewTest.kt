package com.jarvis.developer

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.Robolectric
import android.app.Activity
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DeveloperPlanAnalysisReviewTest {
    private val path = "developer/src/main/java/com/jarvis/developer/Latency.kt"
    private fun plan() = TaskPlanner.Plan(
        id = "plan-1", task = "Improve Developer latency", baseCommitSha = "a".repeat(40),
        analysisId = "b".repeat(64), scope = listOf(path),
        steps = listOf(
            TaskPlanner.Step("analyze", "Confirm analysis", "Review source plan", emptyList(), emptyList()),
            TaskPlanner.Step("implement-1", "Fix latency", "Source-scoped code change", listOf(path), listOf("analyze")),
            TaskPlanner.Step("validate", "Validate", "Check CI", emptyList(), listOf("implement-1")),
            TaskPlanner.Step("review", "Review", "Confirm evidence", emptyList(), listOf("validate"))),
        decisions = listOf(TaskPlanner.Decision(1, null, "Read-only analysis saved", 1000)),
        revision = 0, paused = false, updatedAtMillis = 1000L)

    @Test fun explicitMetadataReviewMustPrecedeOneSavedOwnerCheckpoint() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val dir = Files.createTempDirectory("plan-review-").toFile()
        val store = TaskPlanStore(dir)
        store.save(plan(), null)
        val review = DeveloperPlanAnalysisReview({ dir }, { 1002L })
        assertTrue(review.confirm(activity).contains("No current"))
        assertEquals(TaskPlanner.Status.PENDING, store.loadAll().single().steps[0].status)
        val report = review.review(activity)
        assertTrue(report.contains(path))
        assertTrue(report.contains("a".repeat(40)))
        assertTrue(report.contains("not a live GitHub read"))
        assertEquals(TaskPlanner.Status.PENDING, store.loadAll().single().steps[0].status)
        assertTrue(review.confirm(activity).contains("acknowledged and saved"))
        val saved = store.loadAll().single()
        assertEquals(TaskPlanner.Status.DONE, saved.steps[0].status)
        assertEquals(TaskPlanner.Status.PENDING, saved.steps[1].status)
        assertEquals(1L, saved.revision)
        assertTrue(saved.steps[0].checkpoint.contains("owner-reported"))
        assertTrue(review.confirm(activity).contains("No current"))
    }

    @Test fun stalePlanOrChangedScopeCannotSpendOldAnalysisConsent() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val dir = Files.createTempDirectory("plan-stale-").toFile()
        val store = TaskPlanStore(dir)
        store.save(plan(), null)
        val review = DeveloperPlanAnalysisReview({ dir }, { 1002L })
        review.review(activity)
        store.save(TaskPlanner.note(plan(), "New decision before review", 1001L), 0L)
        assertTrue(review.confirm(activity).contains("changed or could not be saved"))
        assertEquals(TaskPlanner.Status.PENDING, store.loadAll().single().steps[0].status)
        assertTrue(review.confirm(activity).contains("No current"))
    }

    @Test fun pausedPlanCannotBeAuthorizedThroughChat() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val dir = Files.createTempDirectory("plan-paused-").toFile()
        val store = TaskPlanStore(dir)
        store.save(plan(), null)
        val paused = TaskPlanner.pause(plan(), true, 1001L)
        store.save(paused, 0L)
        val review = DeveloperPlanAnalysisReview({ dir })
        assertTrue(review.review(activity).contains("paused or has interrupted"))
        assertTrue(review.confirm(activity).contains("No current"))
        assertEquals(TaskPlanner.Status.PENDING, store.loadAll().single().steps[0].status)
    }

    @Test fun commandIsExactAndDoesNotConflateReviewWithApproval() {
        assertEquals(DeveloperPlanAnalysisChatCommand.Action.REVIEW,
            DeveloperPlanAnalysisChatCommand.match("Review Developer plan"))
        assertEquals(DeveloperPlanAnalysisChatCommand.Action.CONFIRM,
            DeveloperPlanAnalysisChatCommand.match("Approve Developer plan analysis"))
        assertNull(DeveloperPlanAnalysisChatCommand.match("Approve Developer diff"))
        assertNull(DeveloperPlanAnalysisChatCommand.match("Approve Developer plan analysis and publish"))
        assertNull(DeveloperPlanAnalysisChatCommand.match("Review Personal Jarvis plan"))
    }
}
