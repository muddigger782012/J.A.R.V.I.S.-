package com.assistant.core.workflow

import com.assistant.core.learning.ApprovedRoutineRegistry
import com.assistant.core.services.LearnableDeviceAction
import com.assistant.core.services.RoutineExecution
import com.assistant.core.services.RoutineSteps
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class WorkflowEngineTest {
    private val music = LearnableDeviceAction("open_app", mapOf("package" to "com.example.music", "label" to "Music"))

    private class Fixture(actions: List<String>, val store: RoutineWorkflowStore = InMemoryRoutineWorkflowStore()) {
        var registryValue = "[]"
        val registry = ApprovedRoutineRegistry({ registryValue }, { registryValue = it; true })
        val calls = mutableListOf<RoutineWorkflowStep>()
        var preferred: LearnableDeviceAction? = null
        var launchable = true
        var effect: (RoutineWorkflowStep) -> RoutineStepOutcome = { RoutineStepOutcome.Verified }
        val phrase = "focus preparation"

        init {
            val routine = registry.propose(phrase, actions) ?: error("Invalid test routine")
            assertTrue(registry.saveApproved(routine))
        }

        fun engine(): WorkflowEngine = WorkflowEngine(
            approvedRoutine = registry::find,
            executeRoutineStep = { checkpoint, step ->
                assertEquals(step, checkpoint.steps[step.index].step)
                calls.add(step); effect(step)
            },
            resolvePreferred = { preferred },
            canLaunchApp = { launchable },
            store = store
        )

        fun ready(engine: WorkflowEngine, key: String = "voice-turn-one"): RoutineWorkflowCheckpoint {
            val plan = engine.planApprovedRoutine(phrase, key)
            return engine.approveRoutine(plan.id, plan.steps.map { it.step })
        }
    }

    @Test fun existingMetadataCreationApiRemainsAvailable() {
        val engine = WorkflowEngine()
        val workflow = engine.createWorkflow("Morning", "VOICE")
        assertEquals("Morning", workflow.name)
        assertEquals("VOICE", workflow.triggerType)
        assertTrue(workflow.id.isNotBlank())
        assertEquals("PENDING", engine.createTask("Prepare").status)
        assertEquals("DONE", engine.createTask("Prepare", "DONE").status)
    }

    @Test fun planningAndSavedRoutineApprovalDoNotDispatchAndRunRequiresApproval() {
        val f = Fixture(listOf("volume_down", "timer:300"))
        val engine = f.engine()
        val plan = engine.planApprovedRoutine(f.phrase, "turn")
        assertEquals(RoutineWorkflowStatus.AWAITING_APPROVAL, engine.runApprovedRoutine(plan.id).status)
        assertEquals(RoutineWorkflowStatus.AWAITING_APPROVAL, engine.resumeApprovedRoutine(plan.id).status)
        assertTrue(f.calls.isEmpty())
        expectIllegal { engine.approveRoutine(plan.id, plan.steps.reversed().map { it.step }) }
        assertEquals(RoutineWorkflowStatus.READY, engine.approveRoutine(plan.id, plan.steps.map { it.step }).status)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun volumeAndTimerWorkflowReportsRequestedUntilExplicitUserOutcome() {
        val f = Fixture(listOf("volume_down", "timer:300"))
        f.effect = { if (it.target == null) RoutineStepOutcome.Verified else RoutineStepOutcome.AwaitingOutcome }
        val engine = f.engine()
        val ready = f.ready(engine)
        val result = engine.runApprovedRoutine(ready.id)
        assertEquals(listOf("volume_down", "timer:300"), f.calls.map { it.actionId })
        assertEquals("300", f.calls.last().target!!.parameters["seconds"])
        assertEquals(listOf(RoutineStepStatus.VERIFIED, RoutineStepStatus.AWAITING_OUTCOME), result.steps.map { it.status })
        assertEquals(RoutineWorkflowStatus.AWAITING_OUTCOME, result.status)
        val confirmed = engine.recordRoutineOutcome(ready.id, 1, RoutineOutcomeObservation.USER_CONFIRMED, "User checked that the timer is running.")
        assertEquals(RoutineWorkflowStatus.USER_CONFIRMED, confirmed.status)
        assertEquals(RoutineStepStatus.USER_CONFIRMED, confirmed.steps.last().status)
        engine.runApprovedRoutine(ready.id)
        engine.resumeApprovedRoutine(ready.id)
        assertEquals(2, f.calls.size)
    }

    @Test fun exactVolumeAndNamedAppWorkflowFreezesBoundAppAndDoesNotClaimTaskCompletion() {
        val appStep = RoutineSteps.appStep(music)!!
        val f = Fixture(listOf("volume:25", appStep))
        val engine = f.engine()
        val ready = f.ready(engine)
        val result = engine.runApprovedRoutine(ready.id)
        assertEquals(listOf("volume:25", appStep), f.calls.map { it.actionId })
        assertEquals(music, f.calls.last().target)
        // A dispatcher incorrectly calling startActivity success "verified" is capped.
        assertEquals(RoutineStepStatus.REQUESTED, result.steps.last().status)
        assertEquals(RoutineWorkflowStatus.AWAITING_OUTCOME, result.status)
        assertTrue(result.steps.last().detail.contains("not been verified"))
    }

    @Test fun volumeAndUpdaterWorkflowOnlyRequestsUpdaterAndCannotRetryItsLaunch() {
        val f = Fixture(listOf("volume_up", "app_update"))
        f.effect = { if (it.actionId == "app_update") RoutineStepOutcome.Requested else RoutineStepOutcome.Verified }
        val engine = f.engine()
        val ready = f.ready(engine)
        val requested = engine.runApprovedRoutine(ready.id)
        assertEquals(RoutineWorkflowStatus.AWAITING_OUTCOME, requested.status)
        assertEquals(RoutineStepStatus.REQUESTED, requested.steps.last().status)
        repeat(3) { engine.resumeApprovedRoutine(ready.id); engine.runApprovedRoutine(ready.id) }
        assertEquals(2, f.calls.size)
        // This approval authorizes opening the existing updater, never installing an APK.
        assertEquals(ready.id, engine.planApprovedRoutine(f.phrase, "another-turn").id)
        assertEquals(2, f.calls.size)
    }

    @Test fun safeExplicitResumeSkipsCompletedVolumeAndBoundsFailedStepAttempts() {
        val f = Fixture(listOf("volume_down", "volume_up", "app_update"))
        var blocked = true
        f.effect = { if (it.actionId == "volume_up" && blocked) RoutineStepOutcome.NotApplied("Permission denied before dispatch.")
            else if (it.actionId == "app_update") RoutineStepOutcome.Requested else RoutineStepOutcome.Verified }
        val engine = f.engine()
        val ready = f.ready(engine)
        val failed = engine.runApprovedRoutine(ready.id)
        assertEquals(RoutineWorkflowStatus.FAILED, failed.status)
        assertEquals(listOf("volume_down", "volume_up"), f.calls.map { it.actionId })
        engine.runApprovedRoutine(ready.id)
        assertEquals(2, f.calls.size)
        blocked = false
        val resumed = engine.resumeApprovedRoutine(ready.id)
        assertEquals(listOf("volume_down", "volume_up", "volume_up", "app_update"), f.calls.map { it.actionId })
        assertEquals(listOf(1, 2, 1), resumed.steps.map { it.attempts })
        assertEquals(RoutineWorkflowStatus.AWAITING_OUTCOME, resumed.status)
    }

    @Test fun exhaustedKnownNotAppliedFailureCannotBeBypassedWithRunOrAnotherInvocation() {
        val f = Fixture(listOf("volume_down"))
        f.effect = { RoutineStepOutcome.NotApplied("Unavailable before dispatch.") }
        val engine = f.engine()
        val ready = f.ready(engine)
        engine.runApprovedRoutine(ready.id)
        repeat(5) { engine.resumeApprovedRoutine(ready.id) }
        assertEquals(2, f.calls.size)
        assertEquals(ready.id, engine.planApprovedRoutine(f.phrase, "new-turn").id)
        engine.runApprovedRoutine(ready.id)
        assertEquals(2, f.calls.size)
    }

    @Test fun exceptionAfterPotentialEffectIsAmbiguousAndNeverAutomaticallyRetried() {
        val f = Fixture(listOf("volume_down", "timer:300"))
        f.effect = { throw IllegalStateException("Could have applied") }
        val engine = f.engine()
        val ready = f.ready(engine)
        assertEquals(RoutineWorkflowStatus.AMBIGUOUS, engine.runApprovedRoutine(ready.id).status)
        repeat(3) { engine.resumeApprovedRoutine(ready.id); engine.runApprovedRoutine(ready.id) }
        val duplicate = engine.planApprovedRoutine(f.phrase, "duplicate-turn")
        assertEquals(ready.id, duplicate.id)
        assertEquals(1, f.calls.size)
        assertEquals(RoutineStepStatus.PENDING, duplicate.steps.last().status)
    }

    @Test fun legacyFailureIsConservativelyAmbiguousRatherThanProofNoEffectHappened() {
        assertTrue(RoutineStepOutcome.fromRoutineStatus(RoutineExecution.Status.FAILED) is RoutineStepOutcome.Ambiguous)
        val f = Fixture(listOf("volume_up", "app_update"))
        f.effect = { RoutineStepOutcome.fromRoutineStatus(RoutineExecution.Status.FAILED) }
        val engine = f.engine()
        val ready = f.ready(engine)
        assertEquals(RoutineWorkflowStatus.AMBIGUOUS, engine.runApprovedRoutine(ready.id).status)
        engine.resumeApprovedRoutine(ready.id)
        assertEquals(1, f.calls.size)
    }

    @Test fun requestedWithoutTrackingDoesNotBecomeSuccessOrAllowDuplicateTimer() {
        val f = Fixture(listOf("timer:300"))
        f.effect = { RoutineStepOutcome.RequestedWithoutTracking }
        val engine = f.engine()
        val ready = f.ready(engine)
        val result = engine.runApprovedRoutine(ready.id)
        assertEquals(RoutineStepStatus.REQUESTED_WITHOUT_TRACKING, result.steps.single().status)
        engine.resumeApprovedRoutine(ready.id)
        assertEquals(ready.id, engine.planApprovedRoutine(f.phrase, "retry-turn").id)
        assertEquals(1, f.calls.size)
    }

    @Test fun negativeUserOutcomeDoesNotMakeARequestedTimerSafeToRetry() {
        val f = Fixture(listOf("timer:300"))
        f.effect = { RoutineStepOutcome.AwaitingOutcome }
        val engine = f.engine()
        val ready = f.ready(engine)
        engine.runApprovedRoutine(ready.id)
        val failed = engine.recordRoutineOutcome(ready.id, 0, RoutineOutcomeObservation.FAILED, "User could not find the timer.")
        assertEquals(RoutineWorkflowStatus.AMBIGUOUS, failed.status)
        engine.resumeApprovedRoutine(ready.id)
        assertEquals(1, f.calls.size)
    }

    @Test fun updaterBeforeAnotherStepWaitsForEvidenceAndExplicitResume() {
        val f = Fixture(listOf("app_update", "volume_down"))
        f.effect = { if (it.actionId == "app_update") RoutineStepOutcome.Requested else RoutineStepOutcome.Verified }
        val engine = f.engine()
        val ready = f.ready(engine)
        engine.runApprovedRoutine(ready.id)
        assertEquals(listOf("app_update"), f.calls.map { it.actionId })
        val observed = engine.recordRoutineOutcome(ready.id, 0, RoutineOutcomeObservation.USER_CONFIRMED, "User confirmed the intended updater result.")
        assertEquals(RoutineWorkflowStatus.PAUSED, observed.status)
        assertEquals(1, f.calls.size)
        assertEquals(RoutineWorkflowStatus.USER_CONFIRMED, engine.resumeApprovedRoutine(ready.id).status)
        assertEquals(listOf("app_update", "volume_down"), f.calls.map { it.actionId })
    }

    @Test fun cancellationBeforeRunExecutesNothingAndCannotBeResumed() {
        val f = Fixture(listOf("volume_up", "app_update"))
        val engine = f.engine()
        val ready = f.ready(engine)
        assertEquals(RoutineWorkflowStatus.CANCELLED, engine.cancelApprovedRoutine(ready.id).status)
        engine.runApprovedRoutine(ready.id)
        engine.resumeApprovedRoutine(ready.id)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun inFlightCancellationRemainsResponsiveAndPreventsSubsequentStepsAndConcurrentDuplicateRun() {
        val f = Fixture(listOf("volume_down", "timer:300"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.effect = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); RoutineStepOutcome.Verified }
        val engine = f.engine()
        val ready = f.ready(engine)
        val result = AtomicReference<RoutineWorkflowCheckpoint>()
        val failure = AtomicReference<Throwable>()
        val thread = Thread {
            try { result.set(engine.runApprovedRoutine(ready.id)) } catch (t: Throwable) { failure.set(t) }
        }
        thread.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(RoutineWorkflowStatus.RUNNING, engine.routineCheckpoint(ready.id).status)
            assertEquals(RoutineWorkflowStatus.RUNNING, engine.runApprovedRoutine(ready.id).status)
            assertEquals(RoutineWorkflowStatus.CANCELLING, engine.cancelApprovedRoutine(ready.id).status)
        } finally { release.countDown(); thread.join(5_000) }
        assertFalse(thread.isAlive)
        failure.get()?.let { throw AssertionError(it) }
        assertEquals(RoutineWorkflowStatus.CANCELLED, result.get().status)
        assertEquals(RoutineStepStatus.VERIFIED, result.get().steps.first().status)
        assertEquals(RoutineStepStatus.PENDING, result.get().steps.last().status)
        engine.resumeApprovedRoutine(ready.id)
        assertEquals(1, f.calls.size)
    }

    @Test fun cancellationAfterLaunchDoesNotPermitSameUnresolvedLaunchUnderAnotherKey() {
        val f = Fixture(listOf("timer:300"))
        f.effect = { RoutineStepOutcome.AwaitingOutcome }
        val engine = f.engine()
        val ready = f.ready(engine)
        engine.runApprovedRoutine(ready.id)
        assertEquals(RoutineWorkflowStatus.CANCELLED, engine.cancelApprovedRoutine(ready.id).status)
        assertEquals(ready.id, engine.planApprovedRoutine(f.phrase, "new-key").id)
        val confirmed = engine.recordRoutineOutcome(ready.id, 0, RoutineOutcomeObservation.USER_CONFIRMED, "User confirmed existing timer.")
        assertEquals(RoutineWorkflowStatus.CANCELLED, confirmed.status)
        engine.resumeApprovedRoutine(ready.id)
        assertEquals(1, f.calls.size)
        // A genuinely new invocation is allowed only once the old outcome is resolved.
        assertNotEquals(ready.id, engine.planApprovedRoutine(f.phrase, "fresh-key").id)
    }

    @Test fun duplicateInvocationKeysSurviveReconstructionAndFinishedStepsAreNotRepeated() {
        val f = Fixture(listOf("volume_down", "volume_up"))
        var blocked = true
        f.effect = { if (it.actionId == "volume_up" && blocked) RoutineStepOutcome.NotApplied("No effect") else RoutineStepOutcome.Verified }
        var engine = f.engine()
        val ready = f.ready(engine)
        engine.runApprovedRoutine(ready.id)
        assertEquals(ready.id, engine.planApprovedRoutine(f.phrase, "second-key").id)
        blocked = false
        engine = f.engine()
        assertEquals(RoutineWorkflowStatus.FAILED, engine.routineCheckpoint(ready.id).status)
        assertEquals(RoutineWorkflowStatus.VERIFIED, engine.resumeApprovedRoutine(ready.id).status)
        assertEquals(listOf("volume_down", "volume_up", "volume_up"), f.calls.map { it.actionId })
        assertEquals(ready.id, engine.planApprovedRoutine(f.phrase, "second-key").id)
        assertNotEquals(ready.id, engine.planApprovedRoutine(f.phrase, "fresh-key").id)
    }

    @Test fun recoveryOfInFlightLaunchIsAmbiguousAndCannotDispatchAgain() {
        val store = InMemoryRoutineWorkflowStore()
        val f = Fixture(listOf("volume_down", "timer:300"), store)
        var engine = f.engine()
        val ready = f.ready(engine)
        val interrupted = ready.copy(status = RoutineWorkflowStatus.RUNNING, steps = ready.steps.mapIndexed { i, step ->
            step.copy(attempts = 1, status = if (i == 0) RoutineStepStatus.VERIFIED else RoutineStepStatus.RUNNING)
        })
        assertTrue(store.save(interrupted))
        engine = f.engine()
        val recovered = engine.routineCheckpoint(ready.id)
        assertEquals(RoutineWorkflowStatus.AMBIGUOUS, recovered.status)
        assertEquals(RoutineStepStatus.AMBIGUOUS, recovered.steps.last().status)
        engine.runApprovedRoutine(ready.id)
        engine.resumeApprovedRoutine(ready.id)
        assertEquals(ready.id, engine.planApprovedRoutine(f.phrase, "after-restart").id)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun checkpointFailureBeforeDispatchHasNoEffectAndOnlyExplicitResumeMayRetry() {
        val backing = InMemoryRoutineWorkflowStore()
        var failRunning = true
        val store = object : RoutineWorkflowStore {
            override fun load() = backing.load()
            override fun save(checkpoint: RoutineWorkflowCheckpoint): Boolean =
                if (failRunning && checkpoint.steps.any { it.status == RoutineStepStatus.RUNNING }) false else backing.save(checkpoint)
        }
        val f = Fixture(listOf("volume_down"), store)
        val engine = f.engine()
        val ready = f.ready(engine)
        assertEquals(RoutineWorkflowStatus.FAILED, engine.runApprovedRoutine(ready.id).status)
        assertTrue(f.calls.isEmpty())
        failRunning = false
        engine.runApprovedRoutine(ready.id)
        assertTrue(f.calls.isEmpty())
        assertEquals(RoutineWorkflowStatus.VERIFIED, engine.resumeApprovedRoutine(ready.id).status)
        assertEquals(1, f.calls.size)
    }

    @Test fun checkpointFailureAfterDispatchBlocksFurtherEffectsAndRestartRetries() {
        val backing = InMemoryRoutineWorkflowStore()
        val store = object : RoutineWorkflowStore {
            override fun load() = backing.load()
            override fun save(checkpoint: RoutineWorkflowCheckpoint): Boolean =
                if (checkpoint.steps.any { it.status == RoutineStepStatus.VERIFIED }) false else backing.save(checkpoint)
        }
        val f = Fixture(listOf("volume_down", "timer:300"), store)
        var engine = f.engine()
        val ready = f.ready(engine)
        assertEquals(RoutineWorkflowStatus.AMBIGUOUS, engine.runApprovedRoutine(ready.id).status)
        engine.resumeApprovedRoutine(ready.id)
        engine = f.engine()
        assertEquals(RoutineWorkflowStatus.AMBIGUOUS, engine.routineCheckpoint(ready.id).status)
        engine.resumeApprovedRoutine(ready.id)
        assertEquals(1, f.calls.size)
    }

    @Test fun missingOrChangedRegistryApprovalCannotExecutePlannedSteps() {
        val f = Fixture(listOf("volume_down", "app_update"))
        val engine = f.engine()
        val ready = f.ready(engine)
        assertTrue(f.registry.forget(f.phrase))
        assertEquals(RoutineWorkflowStatus.FAILED, engine.runApprovedRoutine(ready.id).status)
        assertTrue(f.calls.isEmpty())
        assertTrue(f.registry.saveApproved(f.registry.propose(f.phrase, listOf("volume_up"))!!))
        assertEquals(RoutineWorkflowStatus.FAILED, engine.resumeApprovedRoutine(ready.id).status)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun preferredBindingIsResolvedOnceAndUnavailableAppPreflightPreventsVolumeEffects() {
        val f = Fixture(listOf("volume_down", "preferred:music"))
        f.preferred = music
        val engine = f.engine()
        val ready = f.ready(engine)
        f.preferred = music.copy(parameters = mapOf("package" to "com.example.other", "label" to "Other"))
        assertEquals(ready.id, engine.planApprovedRoutine(f.phrase, "duplicate-key").id)
        engine.runApprovedRoutine(ready.id)
        assertEquals(music, f.calls.last().target)

        val unavailable = Fixture(listOf("volume_down", RoutineSteps.appStep(music)!!))
        val unavailableEngine = unavailable.engine()
        val unavailableReady = unavailable.ready(unavailableEngine)
        unavailable.launchable = false
        assertEquals(RoutineWorkflowStatus.FAILED, unavailableEngine.runApprovedRoutine(unavailableReady.id).status)
        assertTrue(unavailable.calls.isEmpty())
    }

    @Test fun unapprovedAndUnsupportedPlansAreRejectedBeforeEffects() {
        val f = Fixture(listOf("volume_down"))
        val engine = f.engine()
        expectIllegal { engine.planApprovedRoutine("not saved", "turn") }
        expectIllegal { engine.planApprovedRoutine(f.phrase, " ") }
        for (actions in listOf(listOf("shell:reboot"), listOf("volume_down", "timer:300", "app_update"),
            listOf("timer:300", "volume_down"), listOf("volume_up", "volume_up"))) {
            val bad = WorkflowEngine(approvedRoutine = { ApprovedRoutineRegistry.Routine(f.phrase, actions) }, executeRoutineStep = { _, _ ->
                fail("Unsupported actions must not be dispatched"); RoutineStepOutcome.Verified
            })
            expectIllegal { bad.planApprovedRoutine(f.phrase, "bad-turn") }
        }
        assertTrue(f.calls.isEmpty())
    }

    @Test fun secondRoutineCannotBypassUnresolvedTimerAndRequestKeyCannotChangeMeaning() {
        val f = Fixture(listOf("timer:300"))
        assertTrue(f.registry.saveApproved(f.registry.propose("another preparation", listOf("volume_down", "timer:300"))!!))
        f.effect = { RoutineStepOutcome.Requested }
        val engine = f.engine()
        val ready = f.ready(engine)
        engine.runApprovedRoutine(ready.id)
        expectIllegal { engine.planApprovedRoutine("another preparation", "voice-turn-one") }
        expectIllegal { engine.planApprovedRoutine("another preparation", "new-turn") }
        assertEquals(1, f.calls.size)
    }

    @Test fun mutableReviewSnapshotsCannotChangeLivePlanOrBoundParameters() {
        val f = Fixture(listOf("volume:25", RoutineSteps.appStep(music)!!))
        val engine = f.engine()
        val plan = engine.planApprovedRoutine(f.phrase, "turn")
        val parameters = plan.steps.last().step.target!!.parameters
        if (parameters is MutableMap<String, String>) parameters["package"] = "com.example.attacker"
        expectIllegal { engine.approveRoutine(plan.id, plan.steps.map { it.step }) }
        val actual = engine.routineCheckpoint(plan.id)
        engine.approveRoutine(actual.id, actual.steps.map { it.step })
        engine.runApprovedRoutine(actual.id)
        assertEquals(music, f.calls.last().target)
    }

    @Test fun twoAlreadyPreparedRoutinesCannotDuplicateTheSameUnresolvedLaunch() {
        val f = Fixture(listOf("timer:300"))
        val other = "another preparation"
        assertTrue(f.registry.saveApproved(f.registry.propose(other, listOf("volume_down", "timer:300"))!!))
        f.effect = { RoutineStepOutcome.AwaitingOutcome }
        val engine = f.engine()
        val first = f.ready(engine)
        val secondPlan = engine.planApprovedRoutine(other, "second-turn")
        engine.approveRoutine(secondPlan.id, secondPlan.steps.map { it.step })
        engine.runApprovedRoutine(first.id)
        assertEquals(RoutineWorkflowStatus.FAILED, engine.runApprovedRoutine(secondPlan.id).status)
        engine.resumeApprovedRoutine(secondPlan.id)
        assertEquals(listOf("timer:300"), f.calls.map { it.actionId })
    }

    @Test fun cancellationFromDispatcherPreservesTheInFlightEffectAndStopsLaterSteps() {
        val f = Fixture(listOf("volume_down", "timer:300"))
        val engine = f.engine()
        val ready = f.ready(engine)
        f.effect = {
            assertEquals(RoutineWorkflowStatus.CANCELLING, engine.cancelApprovedRoutine(ready.id).status)
            RoutineStepOutcome.Verified
        }
        val cancelled = engine.runApprovedRoutine(ready.id)
        assertEquals(RoutineWorkflowStatus.CANCELLED, cancelled.status)
        assertEquals(listOf(RoutineStepStatus.VERIFIED, RoutineStepStatus.PENDING), cancelled.steps.map { it.status })
        assertEquals(1, f.calls.size)
    }

    @Test fun failureToSaveFinalStatusCanBeFinalizedWithoutRepeatingEffects() {
        val backing = InMemoryRoutineWorkflowStore()
        var failFinal = true
        val store = object : RoutineWorkflowStore {
            override fun load() = backing.load()
            override fun save(checkpoint: RoutineWorkflowCheckpoint): Boolean =
                if (failFinal && checkpoint.status == RoutineWorkflowStatus.VERIFIED) false else backing.save(checkpoint)
        }
        val f = Fixture(listOf("volume_down"), store)
        val engine = f.engine()
        val ready = f.ready(engine)
        assertEquals(RoutineWorkflowStatus.PAUSED, engine.runApprovedRoutine(ready.id).status)
        failFinal = false
        assertEquals(RoutineWorkflowStatus.VERIFIED, engine.resumeApprovedRoutine(ready.id).status)
        assertEquals(1, f.calls.size)
    }

    @Test fun outcomePersistenceFailureDoesNotAllowRemainingStepsOrDuplicateLaunch() {
        val backing = InMemoryRoutineWorkflowStore()
        var rejectEvidence = false
        val store = object : RoutineWorkflowStore {
            override fun load() = backing.load()
            override fun save(checkpoint: RoutineWorkflowCheckpoint): Boolean =
                if (rejectEvidence && checkpoint.steps.any { it.status == RoutineStepStatus.USER_CONFIRMED }) false else backing.save(checkpoint)
        }
        val f = Fixture(listOf("app_update", "volume_down"), store)
        f.effect = { RoutineStepOutcome.Requested }
        val engine = f.engine()
        val ready = f.ready(engine)
        engine.runApprovedRoutine(ready.id)
        rejectEvidence = true
        assertEquals(RoutineWorkflowStatus.AWAITING_OUTCOME, engine.recordRoutineOutcome(ready.id, 0,
            RoutineOutcomeObservation.USER_CONFIRMED, "User checked the updater.").status)
        engine.resumeApprovedRoutine(ready.id)
        assertEquals(listOf("app_update"), f.calls.map { it.actionId })
    }

    @Test fun missingDurableStateFailsClosedInsteadOfStartingAnEmptyExecutor() {
        val broken = object : RoutineWorkflowStore {
            override fun load(): List<RoutineWorkflowCheckpoint> = error("Store could not be read")
            override fun save(checkpoint: RoutineWorkflowCheckpoint) = true
        }
        try {
            WorkflowEngine(store = broken)
            fail("Unreadable state must not permit duplicate launches")
        } catch (_: IllegalStateException) { }
    }

    private fun expectIllegal(block: () -> Unit) {
        try { block(); fail("Expected IllegalArgumentException") } catch (_: IllegalArgumentException) { }
    }
}
