package com.jarvis.developer

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DeveloperPlanBuildCheckpointTest {
    private val source = "developer/src/main/java/com/jarvis/developer/Latency.kt"
    private val base = "a".repeat(40)
    private val proposal = "b".repeat(64)
    private val sha = "c".repeat(40)
    private val success = PatchPublisher.CiStatus(PatchPublisher.CiState.PASSED,
        "https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/247", "success")

    private fun initial(multi: Boolean = false): TaskPlanner.Plan {
        val scope = if (multi) listOf(source, "developer/src/main/java/com/jarvis/developer/Network.kt")
            else listOf(source)
        val steps = mutableListOf(
            TaskPlanner.Step("analyze", "Confirm analysis", "Review source", emptyList(), emptyList()),
            TaskPlanner.Step("implement-1", "Fix latency", "Edit scoped source", listOf(source), listOf("analyze"))
        )
        if (multi) steps.add(TaskPlanner.Step("implement-2", "Fix network", "Second source step",
            listOf(scope.last()), listOf("implement-1")))
        steps.add(TaskPlanner.Step("validate", "Validate", "Check build", emptyList(),
            listOf(if (multi) "implement-2" else "implement-1")))
        steps.add(TaskPlanner.Step("review", "Review", "Review evidence", emptyList(), listOf("validate")))
        return TaskPlanner.Plan("plan-1", "Fix Developer latency", base, "d".repeat(64), scope,
            steps, listOf(TaskPlanner.Decision(1, null, "Created read-only plan", 1000)),
            0L, false, 1000L)
    }
    private fun store() = TaskPlanStore(Files.createTempDirectory("developer-ci-checkpoint-").toFile())
    private fun ready(store: TaskPlanStore, multi: Boolean = false): TaskPlanner.Plan {
        val original = initial(multi)
        store.save(original, null)
        val acknowledged = TaskPlanner.transition(original, "analyze", TaskPlanner.Status.DONE,
            "Owner reviewed source analysis", 1001)
        store.save(acknowledged, 0L)
        return acknowledged
    }
    private fun result(p: TaskPlanner.Plan): PatchPublisher.CommitResult {
        val packet = requireNotNull(DeveloperPlanContinuation.suggest(listOf(p)).packet)
        return PatchPublisher.CommitResult(packet.id, "Gemini", "gemini-2.5-pro",
            proposal, listOf(source), base, sha,
            PatchEngine.taskBranch(packet.id, proposal, PatchEngine.Target.DEVELOPER),
            "complete source diff", "feature/jarvis-developer", PatchEngine.Target.DEVELOPER)
    }

    @Test fun exactPassedCiAtomicallyCompletesImplementationAndDebugValidationOnly() {
        val storage = store()
        val plan = ready(storage)
        val result = result(plan)
        val report = DeveloperPlanBuildCheckpoint.record(storage, result, success, 1002)
        assertNotNull(report)
        assertTrue(report!!.contains("debug-CI validation"))
        val persisted = storage.loadAll().single()
        assertEquals(TaskPlanner.Status.DONE, persisted.steps[1].status)
        assertEquals(TaskPlanner.Status.DONE, persisted.steps[2].status)
        assertEquals(TaskPlanner.Status.PENDING, persisted.steps[3].status)
        assertEquals(2L, persisted.revision)
        assertEquals(3, persisted.decisions.size)
        assertTrue(persisted.steps[2].checkpoint.contains("automated tests, lint"))
        assertEquals("review", TaskPlanner.next(persisted)?.id)
        assertTrue(persisted.steps[1].checkpoint.contains(sha))
        assertTrue(persisted.steps[1].checkpoint.contains(success.runUrl!!))
        assertTrue(persisted.steps[1].checkpoint.contains("not a canonical merge"))
        assertTrue(persisted.steps[2].checkpoint.contains("NOT a signed candidate"))
        assertNull(DeveloperPlanBuildCheckpoint.record(storage, result, success, 1003))
        assertEquals(persisted, storage.loadAll().single())
    }

    @Test fun malformedValidationDependenciesBlockBothCheckpoints() {
        val storage = store()
        val current = ready(storage)
        val outcome = result(current)
        val changed = TaskPlanner.editStep(current, "validate", "Validate", "Manual gate",
            listOf("analyze"), 1002L)
        storage.save(changed, current.revision)
        assertThrows(IllegalArgumentException::class.java) {
            DeveloperPlanBuildCheckpoint.record(storage, outcome, success, 1003L)
        }
        val persisted = storage.loadAll().single()
        assertEquals(TaskPlanner.Status.PENDING, persisted.steps[1].status)
        assertEquals(TaskPlanner.Status.PENDING, persisted.steps[2].status)
    }

    @Test fun queuedFailedOrUnknownCiCannotMarkWorkComplete() {
        val storage = store()
        val plan = ready(storage)
        val result = result(plan)
        val alternatives = listOf(
            PatchPublisher.CiStatus(PatchPublisher.CiState.MISSING),
            PatchPublisher.CiStatus(PatchPublisher.CiState.QUEUED, success.runUrl),
            PatchPublisher.CiStatus(PatchPublisher.CiState.RUNNING, success.runUrl),
            PatchPublisher.CiStatus(PatchPublisher.CiState.FAILED, success.runUrl, "failure"),
            PatchPublisher.CiStatus(PatchPublisher.CiState.PASSED, null, "success"),
            PatchPublisher.CiStatus(PatchPublisher.CiState.PASSED, "https://wrong.invalid/runs/247", "success"),
            PatchPublisher.CiStatus(PatchPublisher.CiState.PASSED, success.runUrl, null)
        )
        alternatives.forEach {
            assertNull(DeveloperPlanBuildCheckpoint.record(storage, result, it, 1002))
            assertEquals(TaskPlanner.Status.PENDING, storage.loadAll().single().steps[1].status)
        }
    }

    @Test fun sourceOrScopeMismatchAndPausedPlanCannotUseVerifiedCi() {
        val storage = store()
        val plan = ready(storage)
        val result = result(plan)
        assertThrows(IllegalArgumentException::class.java) {
            DeveloperPlanBuildCheckpoint.record(storage, result.copy(taskId = "other-task"), success, 1002)
        }
        assertNull(DeveloperPlanBuildCheckpoint.record(storage, result.copy(baseCommitSha = "f".repeat(40)), success, 1002))
        assertNull(DeveloperPlanBuildCheckpoint.record(storage, result.copy(paths = listOf(
            "developer/src/main/java/com/jarvis/developer/Other.kt")), success, 1002))
        assertThrows(IllegalArgumentException::class.java) {
            DeveloperPlanBuildCheckpoint.record(storage, result.copy(baseBranch =
                "main"), success, 1002)
        }
        val paused = TaskPlanner.pause(plan, true, 1002)
        storage.save(paused, plan.revision)
        assertNull(DeveloperPlanBuildCheckpoint.record(storage, result, success, 1003))
        assertEquals(TaskPlanner.Status.PENDING, storage.loadAll().single().steps[1].status)
    }

    @Test fun multiStepPlanNeverTreatsSiblingBranchesAsCumulativeEvidence() {
        val storage = store()
        val plan = ready(storage, multi = true)
        val result = result(plan)
        assertNull(DeveloperPlanBuildCheckpoint.record(storage, result, success, 1002))
        assertEquals(TaskPlanner.Status.PENDING, storage.loadAll().single().steps[1].status)
    }

    @Test fun noPlanOrUnapprovedAnalysisCannotBeCountedAsVerifiedWork() {
        val storage = store()
        val original = initial()
        val approved = TaskPlanner.transition(original, "analyze", TaskPlanner.Status.DONE,
            "Owner reviewed source analysis", 1001)
        val result = result(approved)
        assertNull(DeveloperPlanBuildCheckpoint.record(storage, result, success, 1002))
        storage.save(original, null)
        assertNull(DeveloperPlanBuildCheckpoint.record(storage, result, success, 1002))
        assertEquals(TaskPlanner.Status.PENDING, storage.loadAll().single().steps[0].status)
    }
}
