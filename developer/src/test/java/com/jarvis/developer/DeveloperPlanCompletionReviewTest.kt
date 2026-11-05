package com.jarvis.developer

import android.app.Activity
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DeveloperPlanCompletionReviewTest {
    companion object {
        val path = "developer/src/main/java/com/jarvis/developer/Latency.kt"
        val base = "a".repeat(40)
        val sha = "c".repeat(40)
        val proposal = "b".repeat(64)
        val ci = PatchPublisher.CiStatus(PatchPublisher.CiState.PASSED,
            "https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/247", "success")
    }

    data class Fixture(
        val directory: File,
        val store: TaskPlanStore,
        val commit: PatchPublisher.CommitResult,
        val activity: Activity
    )

    private fun setup(multi: Boolean = false): Fixture {
        val dir = Files.createTempDirectory("developer-final-review-").toFile()
        val store = TaskPlanStore(dir)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val second = "developer/src/main/java/com/jarvis/developer/Network.kt"
        val scope = if (multi) listOf(path, second) else listOf(path)
        val steps = mutableListOf(
            TaskPlanner.Step("analyze", "Analyze", "Scope", emptyList(), emptyList()),
            TaskPlanner.Step("implement-1", "Implement", "Change", listOf(path), listOf("analyze"))
        )
        if (multi) steps += TaskPlanner.Step("implement-2", "Implement network",
            "Change network", listOf(second), listOf("implement-1"))
        steps += TaskPlanner.Step("validate", "Validate", "CI", emptyList(),
            if (multi) listOf("implement-1", "implement-2") else listOf("implement-1"))
        steps += TaskPlanner.Step("review", "Review", "Final review", emptyList(), listOf("validate"))
        val original = TaskPlanner.Plan("plan-final", "Fix Developer latency", base,
            "d".repeat(64), scope, steps,
            listOf(TaskPlanner.Decision(1, null, "Created plan", 1000L)),
            0L, false, 1000L)
        store.save(original, null)
        val analyzed = TaskPlanner.transition(original, "analyze", TaskPlanner.Status.DONE,
            "Owner accepted analysis", 1001L)
        store.save(analyzed, 0L)
        val id = requireNotNull(DeveloperPlanContinuation.suggest(listOf(analyzed)).packet).id
        val commit = PatchPublisher.CommitResult(id, "Gemini", "gemini-2.5-pro",
            proposal, requireNotNull(DeveloperPlanContinuation.suggest(listOf(analyzed)).packet).paths,
            base, sha,
            PatchEngine.taskBranch(id, proposal, PatchEngine.Target.DEVELOPER),
            "complete reviewed source diff", "feature/jarvis-developer", PatchEngine.Target.DEVELOPER)
        assertNotNull(DeveloperPlanBuildCheckpoint.record(store, commit, ci, 1002L))
        return Fixture(dir, store, commit, activity)
    }

    @Test fun explicitOwnerReviewCompletesLocalCheckpointButNeverClaimsDeployment() {
        val f = setup()
        val review = DeveloperPlanCompletionReview({ f.directory }, { 1003L })
        assertTrue(review.confirm(f.activity, f.commit, ci).contains("No current"))
        val report = review.prepare(f.activity, f.commit, ci)
        assertTrue(report.text.contains(sha))
        assertTrue(report.text.contains(ci.runUrl!!))
        assertTrue(report.text.contains("NOT verified"))
        assertEquals(TaskPlanner.Status.PENDING, f.store.loadAll().single().steps.last().status)
        review.arm(report)
        assertTrue(review.confirm(f.activity, f.commit, ci).contains("final LOCAL review acknowledged"))
        val saved = f.store.loadAll().single()
        assertEquals(TaskPlanner.Status.DONE, saved.steps.last().status)
        assertEquals(3L, saved.revision)
        assertEquals(4, saved.decisions.size)
        assertTrue(saved.steps.last().checkpoint.contains("not canonical merge"))
        assertTrue(review.confirm(f.activity, f.commit, ci).contains("No current"))
    }

    @Test fun twoStepFullScopeBuildCanBeReviewedInChatAsOneExactCiCommit() {
        val f = setup(multi = true)
        val plan = f.store.loadAll().single()
        assertEquals(2, plan.steps.count { it.paths.isNotEmpty() })
        assertEquals(2, f.commit.paths.size)
        assertEquals(4, plan.steps.count { it.status == TaskPlanner.Status.DONE })
        val review = DeveloperPlanCompletionReview({ f.directory }, { 1003L })
        val prepared = review.prepare(f.activity, f.commit, ci)
        assertTrue(prepared.text.contains("Fresh exact-source debug CI"))
        review.arm(prepared)
        assertTrue(review.confirm(f.activity, f.commit, ci).contains("final LOCAL review acknowledged"))
        assertEquals(TaskPlanner.Status.DONE, f.store.loadAll().single().steps.last().status)
    }

    @Test fun failedOrMovedExactCiConsumesTheReviewWithoutChangingStatus() {
        val f = setup()
        val review = DeveloperPlanCompletionReview({ f.directory }, { 1003L })
        assertThrows(IllegalArgumentException::class.java) {
            review.prepare(f.activity, f.commit,
                ci.copy(state = PatchPublisher.CiState.FAILED, conclusion = "failure"))
        }
        review.arm(review.prepare(f.activity, f.commit, ci))
        val moved = ci.copy(runUrl = "https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/248")
        assertTrue(review.confirm(f.activity, f.commit, moved).contains("expired or"))
        assertEquals(TaskPlanner.Status.PENDING, f.store.loadAll().single().steps.last().status)
    }

    @Test fun stalePlanOrExpiredApprovalFailsClosed() {
        val f = setup()
        var clock = 1003L
        val review = DeveloperPlanCompletionReview({ f.directory }, { clock })
        review.arm(review.prepare(f.activity, f.commit, ci))
        val prior = f.store.loadAll().single()
        f.store.save(TaskPlanner.note(prior, "Owner changed risk assessment", 1004L), prior.revision)
        assertTrue(review.confirm(f.activity, f.commit, ci).contains("expired or"))
        assertEquals(TaskPlanner.Status.PENDING, f.store.loadAll().single().steps.last().status)
        review.arm(review.prepare(f.activity, f.commit, ci))
        clock = 1003L + 600_001L
        assertTrue(review.confirm(f.activity, f.commit, ci).contains("expired or"))
    }

    @Test fun exactCommandsCannotExpandScopeOrAuthorizeRelease() {
        assertEquals(DeveloperPlanCompletionChatCommand.Action.REVIEW,
            DeveloperPlanCompletionChatCommand.match("Review Developer completion"))
        assertEquals(DeveloperPlanCompletionChatCommand.Action.CONFIRM,
            DeveloperPlanCompletionChatCommand.match("Confirm Developer completion review"))
        assertNull(DeveloperPlanCompletionChatCommand.match("Confirm Developer completion review and publish"))
        assertNull(DeveloperPlanCompletionChatCommand.match("Review Personal Jarvis completion"))
        assertNull(DeveloperPlanCompletionChatCommand.match("Approve Developer diff"))
    }
}
