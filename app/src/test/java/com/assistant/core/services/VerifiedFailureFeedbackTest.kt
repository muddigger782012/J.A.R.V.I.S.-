package com.assistant.core.services

import com.assistant.core.learning.ValidatedCommandMemory
import org.junit.Assert.*
import org.junit.Test

class VerifiedFailureFeedbackTest {
    private val timer = LearnableDeviceAction("timer", mapOf("seconds" to "300"))
    private val chrome = LearnableDeviceAction("open_app", mapOf("package" to "com.android.chrome", "label" to "Chrome"))

    @Test fun trackedTimerAndAppFailurePauseAcrossRestartUntilVerifiedRecovery() {
        for (action in listOf(timer, chrome)) {
            var saved = "[]"
            var pending: String? = null
            fun memory() = ValidatedCommandMemory({ saved }, { saved = it ?: "[]"; true })
            fun session() = DeviceLearningSession({ pending }, { pending = it; true })
            val phrase = "my saved shortcut"
            assertTrue(memory().recordSuccess(phrase, action.capabilityId, LearnableDeviceAction.capabilities, action.parameters))
            assertEquals(DeviceLearningSession.RequestResult.AWAITING_OUTCOME, session().requestAndTrack(phrase, action, replayed = true) { true })
            val failure = session().consume("it failed")!!
            assertTrue(failure.pending.replayed)
            assertTrue(VerifiedFailureFeedback.pause(failure, memory()))
            assertNull(session().consume("it failed"))
            assertTrue(memory().isPaused(phrase))
            assertNull(memory().match(phrase, LearnableDeviceAction.capabilities))
            assertEquals(1, memory().snapshot().single().failures)
            assertNull(memory().latestParameterized(action.capabilityId))
            val assistant = AssistantMemory({ "{}" }, { fail("feedback/review cannot change assistant memory"); false })
            assertTrue(LearnedCommandReview.render(1, memory(), assistant, emptyList()).contains("paused after 1 reported failure"))
            // A new proposal/approval is not recovery. It must reach a successful outcome.
            assertTrue(session().propose(DeviceLearningSession.Pending(phrase, action, true)))
            val approved = session().consume("yes")!!
            assertTrue(session().propose(approved.pending.copy(awaitingOutcome = true)))
            assertTrue(memory().isPaused(phrase))
            val success = session().consume("it worked")!!
            assertTrue(success.accepted)
            assertTrue(memory().recordSuccess(success.pending.phrase, action.capabilityId, LearnableDeviceAction.capabilities, action.parameters))
            assertFalse(memory().isPaused(phrase))
            assertEquals(action.parameters, memory().match(phrase, LearnableDeviceAction.capabilities)?.parameters)
            assertEquals(0, memory().snapshot().single().failures)
        }
    }

    @Test fun contextualReplayPausesSemanticPhraseAcrossHyphenVariants() {
        var saved = "[]"
        var pending: String? = null
        val memory = ValidatedCommandMemory({ saved }, { saved = it ?: "[]"; true })
        val session = DeviceLearningSession({ pending }, { pending = it; true })
        val reference = ContextualAction("open_preferred", mapOf("slot" to "browser"))
        assertTrue(memory.recordSuccess("go to browser", reference.capabilityId, ContextualAction.capabilities, reference.parameters))
        assertEquals(DeviceLearningSession.RequestResult.AWAITING_OUTCOME, session.requestAndTrack("go-to browser", chrome, replayed = true, contextual = reference) { true })
        assertTrue(VerifiedFailureFeedback.pause(session.consume("it didn't work")!!, memory))
        assertTrue(memory.isPaused("go to browser"))
        assertNull(memory.match("go‑to browser", ContextualAction.capabilities))
        assertFalse(memory.recordSuccess("go to browser", "open_app", LearnableDeviceAction.capabilities, chrome.parameters))
        assertTrue(memory.isPaused("go-to browser"))
        assertTrue(memory.forget("go-to browser"))
        assertFalse(memory.isPaused("go to browser"))
    }

    @Test fun cancellationNewLessonFailureSuccessAndOrdinaryLocalFailuresDoNotPauseAliases() {
        var saved = "[]"
        val memory = ValidatedCommandMemory({ saved }, { saved = it ?: "[]"; true })
        val phrase = "custom timer"
        memory.recordSuccess(phrase, "timer", LearnableDeviceAction.capabilities, timer.parameters)
        for (pending in listOf(
            DeviceLearningSession.Pending(phrase, timer, true),
            DeviceLearningSession.Pending(phrase, timer, true, awaitingOutcome = true),
            DeviceLearningSession.Pending(phrase, timer, false, awaitingOutcome = true)
        )) assertFalse(VerifiedFailureFeedback.pause(DeviceLearningSession.Resolution(pending, false), memory))
        val replay = DeviceLearningSession.Pending(phrase, timer, false, awaitingOutcome = true, replayed = true)
        assertFalse(VerifiedFailureFeedback.pause(DeviceLearningSession.Resolution(replay, true), memory))
        assertFalse(memory.isPaused(phrase))
        assertNotNull(memory.match(phrase, LearnableDeviceAction.capabilities))
        var pending: String? = null
        val session = DeviceLearningSession({ pending }, { pending = it; true })
        assertFalse(session.propose(replay.copy(learnable = true)))
        assertFalse(session.propose(replay.copy(awaitingOutcome = false)))
    }

    @Test fun unrelatedExpiredAndFailedRequestsCannotCreateFailureFeedback() {
        var saved = "[]"
        var pending: String? = null
        var now = 1000L
        val memory = ValidatedCommandMemory({ saved }, { saved = it ?: "[]"; true })
        val session = DeviceLearningSession({ pending }, { pending = it; true }, { now })
        memory.recordSuccess("timer shortcut", "timer", LearnableDeviceAction.capabilities, timer.parameters)
        assertEquals(DeviceLearningSession.RequestResult.FAILED, session.requestAndTrack("timer shortcut", timer, replayed = true) { false })
        assertNull(session.consume("it failed"))
        session.requestAndTrack("timer shortcut", timer, replayed = true) { true }
        assertNull(session.consume("show assistant memory"))
        assertNull(session.consume("it failed"))
        session.requestAndTrack("timer shortcut", timer, replayed = true) { true }
        now += 300001L
        assertNull(session.consume("it failed"))
        assertFalse(memory.isPaused("timer shortcut"))
    }

    @Test fun changedMeaningOrFailedStorageCannotPauseAnUnrelatedOrUnpersistedEntry() {
        var saved = "[]"
        val memory = ValidatedCommandMemory({ saved }, { saved = it ?: "[]"; true })
        val phrase = "timer shortcut"
        memory.recordSuccess(phrase, "timer", LearnableDeviceAction.capabilities, timer.parameters)
        val failure = DeviceLearningSession.Resolution(DeviceLearningSession.Pending(phrase, timer, false, awaitingOutcome = true, replayed = true), false)
        val failedDisk = ValidatedCommandMemory({ saved }, { false })
        assertFalse(VerifiedFailureFeedback.pause(failure, failedDisk))
        assertFalse(memory.isPaused(phrase))
        memory.forget(phrase)
        val newer = timer.copy(parameters = mapOf("seconds" to "600"))
        memory.recordSuccess(phrase, "timer", LearnableDeviceAction.capabilities, newer.parameters)
        assertFalse(VerifiedFailureFeedback.pause(failure, memory))
        assertEquals(newer.parameters, memory.match(phrase, LearnableDeviceAction.capabilities)?.parameters)
        assertFalse(memory.recordFailure("it failed", "timer", timer.parameters))
        assertFalse(memory.recordFailure("unknown phrase", "timer", timer.parameters))
    }
}
