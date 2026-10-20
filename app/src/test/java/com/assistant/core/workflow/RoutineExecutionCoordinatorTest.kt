package com.assistant.core.workflow

import com.assistant.core.learning.ApprovedRoutineRegistry
import com.assistant.core.services.LearnableDeviceAction
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

class RoutineExecutionCoordinatorTest {
    private class Access : RoutineWorkflowAccess {
        override val store = InMemoryRoutineWorkflowStore()
        private val lease = ReentrantLock()
        private val cancellations = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        override fun <T : Any> tryExclusive(block: () -> T): T? {
            if (lease.isHeldByCurrentThread || !lease.tryLock()) return null
            return try { block() } finally { lease.unlock() }
        }
        override fun cancellationRequested(id: String) = id in cancellations
        override fun requestCancellation(id: String) = cancellations.add(id) || id in cancellations
    }

    private class Fixture(val actions: List<String> = listOf("volume_down", "volume_up")) {
        val access = Access()
        var routine: ApprovedRoutineRegistry.Routine? = ApprovedRoutineRegistry.Routine("focus time", actions)
        val calls = mutableListOf<String>()
        var effect: (RoutineWorkflowCheckpoint, RoutineWorkflowStep) -> RoutineStepOutcome = { _, _ -> RoutineStepOutcome.Verified }
        fun coordinator() = RoutineExecutionCoordinator(access, { routine?.takeIf { saved -> it == saved.phrase } },
            { cp, step -> calls.add(step.actionId); effect(cp, step) })
        fun ready(): RoutineWorkflowCheckpoint {
            val engine = WorkflowEngine(approvedRoutine = { routine }, store = access.store)
            val plan = engine.planApprovedRoutine("focus time", "original-turn")
            return engine.approveRoutine(plan.id, plan.steps.map { it.step })
        }
    }

    @Test fun statusAfterReconstructionNeverExecutesReadyStepsAndExplicitResumeDoes() {
        val f = Fixture()
        val saved = f.ready()
        assertEquals(saved.id, f.coordinator().status().checkpoint!!.id)
        assertTrue(f.calls.isEmpty())
        assertEquals(RoutineWorkflowStatus.VERIFIED, f.coordinator().resume().checkpoint!!.status)
        assertEquals(f.actions, f.calls)
        f.coordinator().resume()
        assertEquals(f.actions, f.calls)
    }

    @Test fun interruptedActionStaysAmbiguousAcrossInstancesAndWillNotRepeat() {
        val f = Fixture(listOf("timer:300"))
        val saved = f.ready()
        f.access.store.save(saved.copy(status = RoutineWorkflowStatus.RUNNING,
            steps = saved.steps.map { it.copy(status = RoutineStepStatus.RUNNING, attempts = 1) }))
        assertEquals(RoutineWorkflowStatus.AMBIGUOUS, f.coordinator().status().checkpoint!!.status)
        f.coordinator().resume()
        assertEquals(saved.id, f.coordinator().invoke("focus time", "new-turn").checkpoint!!.id)
        assertTrue(f.calls.isEmpty())
        assertEquals(RoutineWorkflowStatus.USER_CONFIRMED,
            f.coordinator().report(RoutineOutcomeObservation.USER_CONFIRMED).checkpoint!!.status)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun concurrentInstanceCanInspectAndCancelWithoutRecoveringTheLiveDispatch() {
        val f = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.effect = { _, _ -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); RoutineStepOutcome.Verified }
        val outcome = AtomicReference<RoutineExecutionCoordinator.Result>()
        val failure = AtomicReference<Throwable>()
        val worker = Thread { try { outcome.set(f.coordinator().invoke("focus time", "voice-turn")) } catch (e: Throwable) { failure.set(e) } }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val other = f.coordinator()
            val status = other.status()
            assertTrue(status.busy)
            assertEquals(RoutineStepStatus.RUNNING, status.checkpoint!!.steps.first().status)
            assertTrue(other.invoke("focus time", "chat-turn").busy)
            assertTrue(other.report(RoutineOutcomeObservation.USER_CONFIRMED).busy)
            val cancelled = other.cancel()
            assertTrue(cancelled.busy)
            assertEquals(RoutineWorkflowStatus.CANCELLING, cancelled.checkpoint!!.status)
        } finally { release.countDown(); worker.join(5_000) }
        assertFalse(worker.isAlive)
        failure.get()?.let { throw AssertionError(it) }
        assertEquals(RoutineWorkflowStatus.CANCELLED, outcome.get().checkpoint!!.status)
        assertEquals(listOf("volume_down"), f.calls)
        assertEquals(RoutineWorkflowStatus.CANCELLED, f.coordinator().resume().checkpoint!!.status)
        assertEquals(listOf("volume_down"), f.calls)
    }

    @Test fun savedCancellationSurvivesRunnerDeathBeforeTheNextDispatch() {
        val f = Fixture()
        val saved = f.ready()
        assertTrue(f.access.requestCancellation(saved.id))
        assertEquals(RoutineWorkflowStatus.CANCELLED, f.coordinator().status().checkpoint!!.status)
        f.coordinator().resume()
        assertTrue(f.calls.isEmpty())
    }

    @Test fun requestedStepNeedsFreshOutcomeAndResumeCannotRepeatIt() {
        val f = Fixture(listOf("volume:25", "timer:300"))
        f.effect = { _, step -> if (step.index == 1) RoutineStepOutcome.Requested else RoutineStepOutcome.Verified }
        val initial = f.coordinator().invoke("focus time", "first")
        assertEquals(RoutineWorkflowStatus.AWAITING_OUTCOME, initial.checkpoint!!.status)
        f.coordinator().resume()
        assertEquals(f.actions, f.calls)
        assertEquals(RoutineWorkflowStatus.USER_CONFIRMED, f.coordinator().report(RoutineOutcomeObservation.USER_CONFIRMED).checkpoint!!.status)
        assertEquals(RoutineWorkflowStatus.USER_CONFIRMED, f.coordinator().resume().checkpoint!!.status)
        assertEquals(f.actions, f.calls)
    }

    @Test fun interruptionBetweenStepsResumesOnlyPendingStepsAfterAnExplicitRequest() {
        val f = Fixture()
        val ready = f.ready()
        f.access.store.save(ready.copy(status = RoutineWorkflowStatus.RUNNING,
            steps = ready.steps.mapIndexed { index, step -> if (index == 0)
                step.copy(status = RoutineStepStatus.VERIFIED, attempts = 1) else step }))
        assertEquals(RoutineWorkflowStatus.PAUSED, f.coordinator().status().checkpoint!!.status)
        assertTrue(f.calls.isEmpty())
        assertEquals(RoutineWorkflowStatus.VERIFIED, f.coordinator().resume().checkpoint!!.status)
        assertEquals(listOf("volume_up"), f.calls)
    }

    @Test fun staleSessionResultCannotConfirmANewerInvocationWithTheSameNameAndTarget() {
        val f = Fixture(listOf("timer:300"))
        f.effect = { _, _ -> RoutineStepOutcome.Requested }
        val previous = f.coordinator().invoke("focus time", "first").checkpoint!!
        f.coordinator().report(RoutineOutcomeObservation.USER_CONFIRMED)
        val next = f.coordinator().invoke("focus time", "second").checkpoint!!
        assertNotEquals(previous.id, next.id)
        val target = next.steps.single().step.target!!
        val stale = f.coordinator().reportFor(previous.id, "focus time", target, RoutineOutcomeObservation.USER_CONFIRMED)
        assertEquals(RoutineWorkflowStatus.AWAITING_OUTCOME, stale.checkpoint!!.status)
        assertEquals(2, f.calls.size)
    }

    @Test fun removedApprovalStillBlocksRecoveredWork() {
        val f = Fixture()
        f.ready()
        f.routine = null
        assertEquals(RoutineWorkflowStatus.FAILED, f.coordinator().resume().checkpoint!!.status)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun cancellingAnUncertainLaunchCannotAllowAnotherRoutineToBypassItsOutcome() {
        val f = Fixture(listOf("timer:300"))
        f.effect = { _, _ -> RoutineStepOutcome.Requested }
        val previous = f.coordinator().invoke("focus time", "first").checkpoint!!
        f.coordinator().cancel()
        f.routine = ApprovedRoutineRegistry.Routine("quiet time", listOf("volume_down"))
        val blocked = f.coordinator().invoke("quiet time", "second")
        assertEquals(previous.id, blocked.checkpoint!!.id)
        assertEquals(listOf("timer:300"), f.calls)
        assertTrue(blocked.message.contains("uncertain effect"))
        f.coordinator().report(RoutineOutcomeObservation.USER_CONFIRMED)
        f.effect = { _, _ -> RoutineStepOutcome.Verified }
        val next = f.coordinator().invoke("quiet time", "third").checkpoint!!
        assertNotEquals(previous.id, next.id)
        assertEquals(RoutineWorkflowStatus.VERIFIED, next.status)
        assertEquals(listOf("timer:300", "volume_down"), f.calls)
    }

    @Test fun corruptedStorageDoesNotFallBackToUntrackedExecution() {
        var dispatches = 0
        val access = object : RoutineWorkflowAccess {
            override val store = object : RoutineWorkflowStore {
                override fun load(): List<RoutineWorkflowCheckpoint> = error("Unreadable journal")
                override fun save(checkpoint: RoutineWorkflowCheckpoint) = false
            }
            override fun <T : Any> tryExclusive(block: () -> T): T = block()
            override fun cancellationRequested(id: String) = false
            override fun requestCancellation(id: String) = false
        }
        val coordinator = RoutineExecutionCoordinator(access, { ApprovedRoutineRegistry.Routine("focus time", listOf("volume:25")) },
            { _, _ -> dispatches++; RoutineStepOutcome.Verified })
        try { coordinator.invoke("focus time", "turn"); fail("Must preserve failure") } catch (_: IllegalStateException) { }
        assertEquals(0, dispatches)
    }
}
