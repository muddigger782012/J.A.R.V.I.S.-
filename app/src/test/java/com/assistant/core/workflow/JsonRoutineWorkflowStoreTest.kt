package com.assistant.core.workflow

import com.assistant.core.learning.ApprovedRoutineRegistry
import com.assistant.core.services.LearnableDeviceAction
import com.assistant.core.services.RoutineSteps
import org.junit.Assert.*
import org.junit.Test

class JsonRoutineWorkflowStoreTest {
    @Test fun roundTripsAllEvidenceAndBoundTargetsWithoutAndroidStorage() {
        var raw: String? = null
        val app = LearnableDeviceAction("open_app", mapOf("package" to "com.example.music", "label" to "Music"))
        val snapshot = RoutineWorkflowCheckpoint("run", setOf("turn", "duplicate-turn"), "focus time", listOf(
            RoutineStepCheckpoint(RoutineWorkflowStep(0, "volume:25"), RoutineStepStatus.VERIFIED, 1, "Readback matched."),
            RoutineStepCheckpoint(RoutineWorkflowStep(1, RoutineSteps.appStep(app)!!, app), RoutineStepStatus.AWAITING_OUTCOME, 1, "Requested.")
        ), RoutineWorkflowStatus.CANCELLED, approved = true, cancellationRequested = true, detail = "Stopped.")
        val store = JsonRoutineWorkflowStore({ raw }, { raw = it; true })
        assertTrue(store.load().isEmpty())
        assertTrue(store.save(snapshot))
        assertEquals(listOf(snapshot), JsonRoutineWorkflowStore({ raw }, { true }).load())
        assertTrue(store.save(snapshot.copy(detail = "Updated status.")))
        assertEquals(1, store.load().size)
        assertEquals("Updated status.", store.load().single().detail)
    }

    @Test fun interruptedTimerJournalSurvivesEngineAndStorageRecreationWithoutRelaunch() {
        var raw: String? = null
        fun store() = JsonRoutineWorkflowStore({ raw }, { raw = it; true })
        val routine = ApprovedRoutineRegistry.Routine("focus time", listOf("timer:300"))
        var calls = 0
        fun engine() = WorkflowEngine(approvedRoutine = { routine }, executeRoutineStep = { _, _ -> calls++; RoutineStepOutcome.Requested }, store = store())
        val first = engine()
        val plan = first.planApprovedRoutine(routine.phrase, "turn")
        val ready = first.approveRoutine(plan.id, plan.steps.map { it.step })
        assertTrue(store().save(ready.copy(status = RoutineWorkflowStatus.RUNNING, steps = ready.steps.map {
            it.copy(status = RoutineStepStatus.RUNNING, attempts = 1)
        })))
        val recovered = engine()
        assertEquals(RoutineWorkflowStatus.AMBIGUOUS, recovered.routineCheckpoint(plan.id).status)
        recovered.runApprovedRoutine(plan.id)
        recovered.resumeApprovedRoutine(plan.id)
        assertEquals(plan.id, recovered.planApprovedRoutine(routine.phrase, "new-turn").id)
        assertEquals(0, calls)
    }

    @Test fun corruptOrFutureJournalFailsClosedAndIsNeverOverwritten() {
        for (invalid in listOf("not json", "{}", "[{\"version\":2}]")) {
            var writes = 0
            val store = JsonRoutineWorkflowStore({ invalid }, { writes++; true })
            try { store.load(); fail("Invalid state must not become an empty journal") } catch (_: Exception) { }
            val plan = RoutineWorkflowCheckpoint("run", setOf("turn"), "focus time", listOf(RoutineStepCheckpoint(RoutineWorkflowStep(0, "volume_down"))))
            assertFalse(store.save(plan))
            assertEquals(0, writes)
        }
    }

    @Test fun failedOrThrowingCommitIsReportedAndCannotDispatchAnAction() {
        val routine = ApprovedRoutineRegistry.Routine("focus time", listOf("volume_down"))
        for (throwCommit in listOf(false, true)) {
            var calls = 0
            val store = JsonRoutineWorkflowStore({ null }, { if (throwCommit) error("Commit failed") else false })
            val engine = WorkflowEngine(approvedRoutine = { routine }, executeRoutineStep = { _, _ -> calls++; RoutineStepOutcome.Verified }, store = store)
            try { engine.planApprovedRoutine(routine.phrase, "turn"); fail("Planning requires a saved checkpoint") } catch (_: IllegalStateException) { }
            assertEquals(0, calls)
        }
    }
}
