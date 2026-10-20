package com.assistant.core.workflow

import com.assistant.core.learning.ApprovedRoutineRegistry
import com.assistant.core.services.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PreparedGoalExecutionTest {
    @Test fun exactGoalApprovalRunsThroughTheExistingJournalAndCannotBeReplayed() {
        val directory = Files.createTempDirectory("prepared-goal").toFile()
        try {
            val plans = GoalPlanStore(PersonalStateFile(directory, "goal-plans.json")) { 1_000L }
            val plan = GoalPlanning.plan(GoalPlanning.parse("help me focus for 25 minutes")!!, { null }, { null }, { false })!!
            val journal = InMemoryRoutineWorkflowStore()
            val lease = RoutineExecutionLease(directory)
            val access = object : RoutineWorkflowAccess {
                override val store = journal
                override fun <T : Any> tryExclusive(block: () -> T) = lease.tryExclusive(block)
                override fun cancellationRequested(id: String) = false
                override fun requestCancellation(id: String) = true
            }
            val effects = mutableListOf<String>()
            fun coordinator() = RoutineExecutionCoordinator(access, plans::find, { _, step ->
                effects.add(step.actionId)
                if (step.target == null) RoutineStepOutcome.Verified else RoutineStepOutcome.Requested
            })
            assertTrue(plans.propose(plan))
            assertNull(coordinator().status().checkpoint)
            assertTrue(effects.isEmpty())
            try { coordinator().invokePrepared(plan.key, "wrong-review") { false }; fail("Must consume exact approval") } catch (_: IllegalStateException) { }
            assertTrue(effects.isEmpty())
            val result = coordinator().invokePrepared(plan.key, "approved-turn") { plans.approve(plan) }.checkpoint!!
            assertEquals(plan.actions, effects)
            assertEquals(RoutineWorkflowStatus.AWAITING_OUTCOME, result.status)
            assertEquals(plan.key, GoalPlanStore(PersonalStateFile(directory, "goal-plans.json")).current()!!.key)
            coordinator().invokePrepared(plan.key, "duplicate-turn") { fail("Active goal must not consume another approval"); false }
            assertEquals(2, effects.size)
            coordinator().report(RoutineOutcomeObservation.USER_CONFIRMED)
            try { coordinator().invokePrepared(plan.key, "stale-review") { plans.approve(plan) }; fail("Consumed review cannot start again") } catch (_: IllegalStateException) { }
            assertEquals(2, effects.size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun interruptedGoalApprovalNeedsAFreshExactApprovalAndNeverStartsOnStatusOrResume() {
        val directory = Files.createTempDirectory("goal-approval-recovery").toFile()
        try {
            val plans = GoalPlanStore(PersonalStateFile(directory, "goal-plans.json")) { 1_000L }
            val plan = GoalPlanning.plan(GoalPlanning.parse("help me focus for five minutes")!!, { null }, { null }, { false })!!
            assertTrue(plans.propose(plan)); assertTrue(plans.approve(plan))
            val journal = InMemoryRoutineWorkflowStore()
            val pending = WorkflowEngine(approvedRoutine = plans::find, store = journal).planApprovedRoutine(plan.key, "interrupted-turn")
            val access = object : RoutineWorkflowAccess {
                override val store = journal
                override fun <T : Any> tryExclusive(block: () -> T) = block()
                override fun cancellationRequested(id: String) = false
                override fun requestCancellation(id: String) = true
            }
            var effects = 0
            val coordinator = RoutineExecutionCoordinator(access, plans::find, { _, _ -> effects++; RoutineStepOutcome.Requested })
            assertEquals(RoutineWorkflowStatus.AWAITING_APPROVAL, coordinator.status().checkpoint!!.status)
            coordinator.resume()
            assertEquals(0, effects)
            try { coordinator.approvePrepared(pending.id, pending.steps.map { it.step }.reversed()); fail("Changed review must fail") } catch (_: IllegalArgumentException) { }
            assertEquals(0, effects)
            coordinator.approvePrepared(pending.id, pending.steps.map { it.step })
            assertEquals(1, effects) // First requested result stops later steps until evidence arrives.
        } finally { directory.deleteRecursively() }
    }

    @Test fun anotherUnresolvedRoutineBlocksGoalApprovalWithoutConsumingItsReview() {
        val journal = InMemoryRoutineWorkflowStore()
        val routine = ApprovedRoutineRegistry.Routine("old routine", listOf("timer:300"))
        val access = object : RoutineWorkflowAccess {
            override val store = journal
            override fun <T : Any> tryExclusive(block: () -> T) = block()
            override fun cancellationRequested(id: String) = false
            override fun requestCancellation(id: String) = true
        }
        var effects = 0
        val coordinator = RoutineExecutionCoordinator(access, { routine }, { _, _ -> effects++; RoutineStepOutcome.Requested })
        val old = coordinator.invoke(routine.phrase, "old-turn").checkpoint!!
        var consumed = false
        val blocked = coordinator.invokePrepared("new goal", "new-turn") { consumed = true; true }
        assertEquals(old.id, blocked.checkpoint!!.id)
        assertFalse(consumed)
        assertEquals(1, effects)
    }

    @Test fun failedApprovalCommitCanBeExplicitlyReapprovedOnlyBeforeAnyDispatch() {
        val routine = ApprovedRoutineRegistry.Routine("prepared goal", listOf("volume_down", "timer:300"))
        val backing = InMemoryRoutineWorkflowStore()
        var rejectApproval = true
        val journal = object : RoutineWorkflowStore {
            override fun load() = backing.load()
            override fun save(checkpoint: RoutineWorkflowCheckpoint): Boolean =
                if (rejectApproval && checkpoint.status == RoutineWorkflowStatus.READY) false else backing.save(checkpoint)
        }
        val access = object : RoutineWorkflowAccess {
            override val store = journal
            override fun <T : Any> tryExclusive(block: () -> T) = block()
            override fun cancellationRequested(id: String) = false
            override fun requestCancellation(id: String) = true
        }
        var effects = 0
        val coordinator = RoutineExecutionCoordinator(access, { routine }, { _, _ -> effects++; RoutineStepOutcome.Requested })
        val failed = coordinator.invokePrepared(routine.phrase, "first") { true }.checkpoint!!
        assertEquals(RoutineWorkflowStatus.FAILED, failed.status)
        assertFalse(failed.approved)
        assertEquals(0, effects)
        coordinator.resume()
        assertEquals(0, effects)
        rejectApproval = false
        coordinator.approvePrepared(failed.id, failed.steps.map { it.step })
        assertEquals(1, effects)
        try { coordinator.approvePrepared(failed.id, failed.steps.map { it.step }); fail("In-flight/requested steps cannot be reapproved") }
        catch (_: IllegalArgumentException) { }
        assertEquals(1, effects)
    }
}
