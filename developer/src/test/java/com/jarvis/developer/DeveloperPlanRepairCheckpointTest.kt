package com.jarvis.developer

import android.app.Activity
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real persisted-plan and owner-review acceptance for a repaired child build. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DeveloperPlanRepairCheckpointTest {
    private val first = "developer/src/main/java/com/jarvis/developer/Latency.kt"
    private val second = "developer/src/main/java/com/jarvis/developer/Network.kt"
    private val base = "a".repeat(40)
    private val failedSha = "c".repeat(40)
    private val repairSha = "e".repeat(40)
    private val failCi = PatchPublisher.CiStatus(PatchPublisher.CiState.FAILED,
        "https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/23", "failure")
    private val passCi = PatchPublisher.CiStatus(PatchPublisher.CiState.PASSED,
        "https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/24", "success")

    private data class Fixture(val directory: java.io.File, val store: TaskPlanStore, val activity: Activity,
        val origin: DeveloperPlanRepairCheckpoint.Origin,
        val repaired: PatchPublisher.CommitResult)

    private fun fixture(): Fixture {
        val directory = Files.createTempDirectory("developer-repair-lineage-").toFile()
        val store = TaskPlanStore(directory)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val planned = TaskPlanner.Plan("original-plan", "Fix Developer latency and network",
            base, "d".repeat(64), listOf(first, second), listOf(
                TaskPlanner.Step("analyze", "Analyze", "Confirm planned scope", emptyList(), emptyList()),
                TaskPlanner.Step("implement-1", "Latency", "Modify latency", listOf(first), listOf("analyze")),
                TaskPlanner.Step("implement-2", "Network", "Modify network", listOf(second), listOf("implement-1")),
                TaskPlanner.Step("validate", "Validate", "Verify exact debug CI", emptyList(),
                    listOf("implement-1", "implement-2")),
                TaskPlanner.Step("review", "Final review", "Owner accepts evidence",
                    emptyList(), listOf("validate"))
            ), listOf(TaskPlanner.Decision(1, null, "Created original plan", 1000L)),
            0L, false, 1000L)
        store.save(planned, null)
        val analyzed = TaskPlanner.transition(planned, "analyze", TaskPlanner.Status.DONE,
            "Owner explicitly accepted analysis", 1001L)
        store.save(analyzed, 0L)
        val packet = requireNotNull(DeveloperPlanContinuation.suggest(listOf(analyzed)).packet)
        val initial = PatchPublisher.CommitResult(packet.id, "Gemini", "gemini-2.5-pro",
            "b".repeat(64), packet.paths, base, failedSha,
            PatchEngine.taskBranch(packet.id, "b".repeat(64), PatchEngine.Target.DEVELOPER),
            "Reviewed cumulative source diff", "feature/jarvis-developer", PatchEngine.Target.DEVELOPER)
        val evidence = BuildDiagnostics.parse(failedSha, initial.branch, 23, 1,
            "completed", "failure",
            "e: $first:2:1 Unresolved reference: compiler_fixture\n",
            PatchEngine.Target.DEVELOPER)
        assertNotNull(DeveloperFailureRepairDraft.from(initial, failCi, evidence))
        val repairedId = "repair-child-one"
        val repaired = PatchPublisher.CommitResult(repairedId, "Gemini", "gemini-2.5-pro",
            "f".repeat(64), listOf(first), failedSha, repairSha,
            PatchEngine.taskBranch(repairedId, "f".repeat(64), PatchEngine.Target.DEVELOPER),
            "Owner-reviewed repair diff", initial.branch, PatchEngine.Target.DEVELOPER)
        return Fixture(directory, store, activity,
            DeveloperPlanRepairCheckpoint.Origin(initial, failCi, evidence), repaired)
    }

    @Test fun passingChildBuildCompletesOriginalScopeButNotFinalOwnerReview() {
        val f = fixture()
        assertNull(DeveloperPlanBuildCheckpoint.record(f.store, f.repaired, passCi, 1002L))
        val receipt = DeveloperPlanRepairCheckpoint.record(f.store, f.origin, f.repaired, passCi, 1002L)
        assertNotNull(receipt)
        assertTrue(receipt!!.contains("original failed commit"))
        val plan = f.store.loadAll().single()
        assertEquals(2L, plan.revision)
        assertEquals(listOf(TaskPlanner.Status.DONE, TaskPlanner.Status.DONE,
            TaskPlanner.Status.DONE, TaskPlanner.Status.DONE, TaskPlanner.Status.PENDING),
            plan.steps.map { it.status })
        assertTrue(plan.steps[1].checkpoint.contains(f.origin.original.commitSha))
        assertTrue(plan.steps[1].checkpoint.contains(repairSha))
        assertTrue(plan.steps[1].checkpoint.contains(failCi.runUrl!!))
        assertTrue(plan.steps[3].checkpoint.contains(passCi.runUrl!!))
        assertEquals("review", TaskPlanner.next(plan)?.id)
        assertNull(DeveloperPlanRepairCheckpoint.record(f.store, f.origin, f.repaired, passCi, 1003L))
        assertEquals(plan, f.store.loadAll().single())
        val review = DeveloperPlanCompletionReview({ f.directory }, { 1003L })
        val prepared = review.prepare(f.activity, f.repaired, passCi)
        assertTrue(prepared.text.contains(repairSha))
        assertTrue(prepared.text.contains("Original canonical source"))
        assertEquals(TaskPlanner.Status.PENDING, f.store.loadAll().single().steps.last().status)
    }

    @Test fun savedExactRepairReceiptSupportsOneFreshOwnerCompletionReview() {
        val f = fixture()
        assertNotNull(DeveloperPlanRepairCheckpoint.record(f.store, f.origin, f.repaired, passCi, 1002L))
        val review = DeveloperPlanCompletionReview({ f.directory }, { 1003L })
        assertTrue(review.confirm(f.activity, f.repaired, passCi).contains("No current"))
        val prepared = review.prepare(f.activity, f.repaired, passCi)
        review.arm(prepared)
        assertTrue(review.confirm(f.activity, f.repaired, passCi)
            .contains("final LOCAL review acknowledged"))
        assertEquals(TaskPlanner.Status.DONE, f.store.loadAll().single().steps.last().status)
        assertTrue(review.confirm(f.activity, f.repaired, passCi).contains("No current"))
    }

    @Test fun unrelatedParentOrIncorrectScopeCannotCompleteOriginalTask() {
        val f = fixture()
        val invalid = listOf(
            f.repaired.copy(baseCommitSha = "9".repeat(40)),
            f.repaired.copy(baseBranch = "feature/jarvis-developer"),
            f.repaired.copy(paths = listOf(second))
        )
        invalid.forEach { repaired ->
            assertNull(DeveloperPlanRepairCheckpoint.record(f.store, f.origin, repaired, passCi, 1002L))
            assertEquals(TaskPlanner.Status.PENDING, f.store.loadAll().single().steps[1].status)
        }
    }

    @Test fun staleOrFailedCiAndMismatchedOriginalEvidenceNeverAdvancePlan() {
        val f = fixture()
        assertNull(DeveloperPlanRepairCheckpoint.record(f.store, f.origin, f.repaired,
            passCi.copy(state = PatchPublisher.CiState.FAILED, conclusion = "failure"), 1002L))
        assertNull(DeveloperPlanRepairCheckpoint.record(f.store, f.origin.copy(
            failedCi = failCi.copy(runUrl =
                "https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/999")),
            f.repaired, passCi, 1002L))
        assertNull(DeveloperPlanRepairCheckpoint.record(f.store,
            f.origin.copy(original = f.origin.original.copy(paths = listOf(first))),
            f.repaired, passCi, 1002L))
        assertEquals(1L, f.store.loadAll().single().revision)
    }
}
