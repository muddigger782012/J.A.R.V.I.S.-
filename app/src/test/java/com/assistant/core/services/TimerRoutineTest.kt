package com.assistant.core.services

import com.assistant.core.learning.ApprovedRoutineRegistry
import org.junit.Assert.*
import org.junit.Test

class TimerRoutineTest {
    private fun proposal(steps: String) = RoutineProposal.fromLesson(FallbackIntentLesson("CREATE_ROUTINE", .95,
        mapOf("phrase" to "focus time", "steps" to steps), learnable = true))

    @Test fun approvedTimerCompositionSurvivesRestartWithoutRunningDuringSave() {
        var saved = "[]"
        var pending: String? = null
        fun registry() = ApprovedRoutineRegistry({ saved }, { saved = it; true })
        fun approvals() = RoutineConfirmationStore({ pending }, { pending = it; true }, { 1_000L })
        val routine = proposal("volume_down,timer:300")!!
        assertTrue(approvals().propose(routine))
        assertNull(registry().find("focus time"))
        assertTrue(registry().saveApproved(approvals().consume("confirm routine")!!.routine))
        assertEquals(routine, registry().find("focus time"))
        assertEquals("start a 300-second timer", RoutineProposal.describe("timer:300"))
        val requested = mutableListOf<String>()
        val result = RoutineExecution.run(registry().find("focus time")!!.actions) { step ->
            requested.add(step)
            if (step == "volume_down") RoutineExecution.Status.VERIFIED else RoutineExecution.Status.AWAITING_OUTCOME
        }
        assertEquals(routine.actions, requested)
        assertTrue(result.awaitingOutcome)
    }

    @Test fun rejectsUnsafeDurationsMultipleTimersAndUiLaunchConflictsBeforeAnyStep() {
        for (steps in listOf("timer:0", "timer:-1", "timer:+5", "timer:0300", "timer:86401", "timer:9999999999", "timer:five",
            "timer:300,volume_down", "timer:300,timer:600", "app_update,timer:300", "volume_down,timer:300;reboot", "shell:300")) {
            assertNull(proposal(steps))
            var called = false
            val result = RoutineExecution.run(steps.split(',')) { called = true; RoutineExecution.Status.REQUESTED }
            assertFalse(called)
            assertTrue(result.steps.isEmpty())
        }
        assertNotNull(proposal("timer:1"))
        assertNotNull(proposal("timer:86400"))
    }

    @Test fun requestDoesNotBecomeVerifiedMemoryUntilLocalUserOutcome() {
        var saved = "{}"
        var pending: String? = null
        val memory = AssistantMemory({ saved }, { saved = it; true })
        fun session() = DeviceLearningSession({ pending }, { pending = it; true })
        val routine = proposal("volume_down,timer:300")!!
        val result = RoutineExecution.run(routine.actions) { step ->
            val timer = RoutineSteps.timerAction(step)
            if (timer == null) RoutineExecution.Status.VERIFIED
            else when (session().requestAndTrack(routine.phrase, timer) { true }) {
                DeviceLearningSession.RequestResult.AWAITING_OUTCOME -> RoutineExecution.Status.AWAITING_OUTCOME
                else -> RoutineExecution.Status.FAILED
            }
        }
        assertTrue(result.awaitingOutcome)
        assertNull(memory.mostRecentConfirmed())
        val outcome = session().consume("it works")!!
        assertTrue(outcome.accepted)
        assertFalse(outcome.pending.learnable)
        assertFalse(outcome.pending.replayed)
        assertTrue(memory.recordConfirmed(outcome.pending.action))
        assertEquals("300", memory.mostRecentConfirmed()!!.parameters["seconds"])
        assertNull(session().consume("it works"))
    }

    @Test fun failureStopsSubsequentStepsAndDoesNotCreateRecentMemory() {
        var pending: String? = null
        val session = DeviceLearningSession({ pending }, { pending = it; true })
        val called = mutableListOf<String>()
        val routine = proposal("volume_down,timer:300")!!
        val result = RoutineExecution.run(routine.actions) { step -> called.add(step); RoutineExecution.Status.FAILED }
        assertEquals(listOf("volume_down"), called)
        assertFalse(result.awaitingOutcome)
        val timer = RoutineSteps.timerAction("timer:300")!!
        assertEquals(DeviceLearningSession.RequestResult.FAILED, session.requestAndTrack(routine.phrase, timer) { false })
        assertNull(session.consume("it worked"))
        assertEquals(DeviceLearningSession.RequestResult.AWAITING_OUTCOME, session.requestAndTrack(routine.phrase, timer) { true })
        assertFalse(session.consume("it failed")!!.accepted)
        assertNull(session.consume("it works"))
    }

    @Test fun failedTrackingDoesNotPretendTheTimerWasVerifiedOrRunItTwice() {
        var calls = 0
        val session = DeviceLearningSession({ null }, { false })
        val result = RoutineExecution.run(listOf("timer:300")) { step ->
            val request = session.requestAndTrack("focus time", RoutineSteps.timerAction(step)!!) { calls++; true }
            assertEquals(DeviceLearningSession.RequestResult.REQUESTED_WITHOUT_TRACKING, request)
            RoutineExecution.Status.REQUESTED_WITHOUT_TRACKING
        }
        assertEquals(1, calls)
        assertTrue(result.missingOutcomeTracking)
        assertFalse(result.awaitingOutcome)
    }
}
