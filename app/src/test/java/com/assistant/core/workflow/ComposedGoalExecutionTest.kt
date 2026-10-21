package com.assistant.core.workflow

import com.assistant.core.services.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ComposedGoalExecutionTest {
    private class Fixture {
        val directory = Files.createTempDirectory("composed-execution").toFile()
        val music = LearnableDeviceAction("open_app", mapOf("package" to "com.example.music", "label" to "Music"))
        val maps = LearnableDeviceAction("open_app", mapOf("package" to "com.example.maps", "label" to "Maps"))
        var raw: String? = null
        var rejectAfterDispatch = false
        var failOutcomeAt: Int? = null
        var unavailable: String? = null
        val calls = mutableListOf<RoutineWorkflowStep>()
        val plan = GoalPlanning.plan(GoalPlanning.parse("prepare my drive")!!, { if (it == "music") music else maps }, { null }, { true })!!
        fun plans() = GoalPlanStore(PersonalStateFile(directory, "goal-plans.json")) { 1_000L }
        fun store() = JsonRoutineWorkflowStore({ raw }, { if (rejectAfterDispatch) false else { raw = it; true } })
        fun engine() = WorkflowEngine(approvedRoutine = plans()::find, store = store(), canLaunchApp = { it != unavailable },
            executeRoutineStep = { _, step ->
                calls.add(step)
                if (calls.size == failOutcomeAt) rejectAfterDispatch = true
                if (step.target == null) RoutineStepOutcome.Verified else RoutineStepOutcome.Requested
            })
        fun ready(): Pair<WorkflowEngine, RoutineWorkflowCheckpoint> {
            assertTrue(plans().propose(plan)); assertTrue(plans().approve(plan))
            val engine = engine(); val pending = engine.planApprovedRoutine(plan.key, "explicit-goal-turn")
            return engine to engine.approveRoutine(pending.id, pending.steps.map { it.step })
        }
    }
    private fun fixture(block: (Fixture) -> Unit) { val f = Fixture(); try { block(f) } finally { f.directory.deleteRecursively() } }

    @Test fun eachExternalStepRequiresItsOwnEvidenceAndExplicitResumeAcrossReconstruction() = fixture { f ->
        val (engine, ready) = f.ready()
        assertTrue(f.calls.isEmpty())
        val first = engine.runApprovedRoutine(ready.id)
        assertEquals(listOf("volume:50", f.plan.actions[1]), f.calls.map { it.actionId })
        assertEquals(RoutineWorkflowStatus.AWAITING_OUTCOME, first.status)
        assertEquals(RoutineStepStatus.PENDING, first.steps[2].status)
        f.engine().resumeApprovedRoutine(ready.id)
        assertEquals(2, f.calls.size)
        val checked = f.engine().recordRoutineOutcome(ready.id, 1, RoutineOutcomeObservation.USER_CONFIRMED, "Synthetic owner checked music setup")
        assertEquals(RoutineWorkflowStatus.PAUSED, checked.status)
        assertEquals(2, f.calls.size)
        val second = f.engine().resumeApprovedRoutine(ready.id)
        assertEquals(3, f.calls.size); assertEquals(f.maps, f.calls.last().target)
        assertEquals(RoutineWorkflowStatus.AWAITING_OUTCOME, second.status)
        f.engine().planApprovedRoutine(f.plan.key, "explicit-goal-turn")
        f.engine().resumeApprovedRoutine(ready.id)
        assertEquals(3, f.calls.size)
        val complete = f.engine().recordRoutineOutcome(ready.id, 2, RoutineOutcomeObservation.USER_CONFIRMED, "Synthetic owner checked Maps request")
        assertEquals(RoutineWorkflowStatus.USER_CONFIRMED, complete.status)
        f.engine().resumeApprovedRoutine(ready.id)
        assertEquals(3, f.calls.size)
    }

    @Test fun missingLaterAppBlocksAllInitialEffectsAndAChangedLaterTargetBlocksResume() = fixture { f ->
        assertTrue(f.plans().propose(f.plan)); assertTrue(f.plans().approve(f.plan))
        f.unavailable = "com.example.maps"
        try { f.engine().planApprovedRoutine(f.plan.key, "unavailable"); fail("All targets must resolve before any action") } catch (_: IllegalArgumentException) { }
        assertTrue(f.calls.isEmpty())
        f.unavailable = null
        val engine = f.engine(); val pending = engine.planApprovedRoutine(f.plan.key, "available")
        engine.approveRoutine(pending.id, pending.steps.map { it.step }); engine.runApprovedRoutine(pending.id)
        f.engine().recordRoutineOutcome(pending.id, 1, RoutineOutcomeObservation.USER_CONFIRMED, "Synthetic checked first app")
        f.unavailable = "com.example.maps"
        assertEquals(RoutineWorkflowStatus.FAILED, f.engine().resumeApprovedRoutine(pending.id).status)
        assertEquals(2, f.calls.size)
    }

    @Test fun failedResultAndCancellationCannotAdvanceOrRepeatTheCurrentApp() = fixture { f ->
        val (engine, ready) = f.ready(); engine.runApprovedRoutine(ready.id)
        assertEquals(RoutineWorkflowStatus.AMBIGUOUS, f.engine().recordRoutineOutcome(ready.id, 1, RoutineOutcomeObservation.FAILED, "Synthetic failure report").status)
        f.engine().resumeApprovedRoutine(ready.id)
        assertEquals(2, f.calls.size)
        f.engine().cancelApprovedRoutine(ready.id)
        f.engine().recordRoutineOutcome(ready.id, 1, RoutineOutcomeObservation.USER_CONFIRMED, "Synthetic late report")
        f.engine().resumeApprovedRoutine(ready.id)
        assertEquals(2, f.calls.size)
        assertEquals(RoutineWorkflowStatus.CANCELLED, f.engine().routineCheckpoint(ready.id).status)
    }

    @Test fun interruptionAtANonfinalAppRemainsUncertainUntilCheckedAndNeverDispatchesOnRead() = fixture { f ->
        val (engine, ready) = f.ready(); val requested = engine.runApprovedRoutine(ready.id)
        val interrupted = requested.copy(status = RoutineWorkflowStatus.RUNNING, steps = requested.steps.mapIndexed { index, step ->
            if (index == 1) step.copy(status = RoutineStepStatus.RUNNING) else step
        })
        assertTrue(f.store().save(interrupted))
        val restored = f.engine()
        assertEquals(RoutineWorkflowStatus.AMBIGUOUS, restored.routineCheckpoint(ready.id).status)
        restored.resumeApprovedRoutine(ready.id)
        assertEquals(2, f.calls.size)
        restored.recordRoutineOutcome(ready.id, 1, RoutineOutcomeObservation.USER_CONFIRMED, "Synthetic checked interrupted app")
        assertEquals(2, f.calls.size)
        restored.resumeApprovedRoutine(ready.id)
        assertEquals(3, f.calls.size); assertEquals(f.maps, f.calls.last().target)
    }

    @Test fun aChangedApprovedDefinitionCannotReplaceRemainingFrozenTargets() = fixture { f ->
        val (engine, ready) = f.ready(); engine.runApprovedRoutine(ready.id)
        engine.recordRoutineOutcome(ready.id, 1, RoutineOutcomeObservation.USER_CONFIRMED, "Synthetic first app checked")
        val other = GoalPlanning.plan(GoalPlanning.parse("plan a task to set volume to twenty percent")!!, { null }, { null }, { false })!!
        f.plans().propose(other); f.plans().approve(other)
        assertEquals(RoutineWorkflowStatus.FAILED, f.engine().resumeApprovedRoutine(ready.id).status)
        assertEquals(2, f.calls.size)
    }

    @Test fun failedOutcomePersistenceAtAMiddleAppRequiresCheckedRecoveryAndNeverRepeatsTheRequest() = fixture { f ->
        val (engine, ready) = f.ready(); f.failOutcomeAt = 2
        assertEquals(RoutineWorkflowStatus.AMBIGUOUS, engine.runApprovedRoutine(ready.id).status)
        assertEquals(2, f.calls.size)
        f.engine().resumeApprovedRoutine(ready.id)
        assertEquals(2, f.calls.size)
        f.rejectAfterDispatch = false
        val restored = f.engine()
        assertEquals(RoutineWorkflowStatus.AMBIGUOUS, restored.routineCheckpoint(ready.id).status)
        restored.recordRoutineOutcome(ready.id, 1, RoutineOutcomeObservation.USER_CONFIRMED, "Synthetic checked request after storage recovery")
        assertEquals(2, f.calls.size)
        restored.resumeApprovedRoutine(ready.id)
        assertEquals(3, f.calls.size)
        assertEquals(f.maps, f.calls.last().target)
    }

    @Test fun aForgedBindingOrAnOrdinaryRoutineCannotOptIntoHostOnlyComposition() = fixture { f ->
        val (_, ready) = f.ready()
        val wrong = ready.copy(steps = ready.steps.mapIndexed { index, step -> if (index == 1)
            step.copy(step = step.step.copy(target = f.maps)) else step })
        assertTrue(f.store().save(wrong))
        try { f.engine(); fail("Stored target must match its host binding") } catch (_: IllegalArgumentException) { }
        val ordinary = com.assistant.core.learning.ApprovedRoutineRegistry.Routine("ordinary routine", f.plan.actions)
        try { WorkflowEngine(approvedRoutine = { ordinary }, canLaunchApp = { true }).planApprovedRoutine(ordinary.phrase, "ordinary"); fail("Saved routine rules stay strict") }
        catch (_: IllegalArgumentException) { }
        assertTrue(f.calls.isEmpty())
    }
}
