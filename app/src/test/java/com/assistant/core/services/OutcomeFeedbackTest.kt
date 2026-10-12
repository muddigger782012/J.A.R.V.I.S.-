package com.assistant.core.services

import com.assistant.core.learning.ValidatedCommandMemory
import org.junit.Assert.*
import org.junit.Test

class OutcomeFeedbackTest {
    private val chrome = LearnableDeviceAction("open_app", mapOf("package" to "com.android.chrome", "label" to "Chrome"))

    @Test fun screenshotSuccessWordingIsConsumedLocallyOnceAfterRestart() {
        for (phrase in listOf("It works", "It work", "It worked!", "That works", "It succeeded", "  IT   WORKS!  ")) {
            var raw: String? = null
            fun session() = DeviceLearningSession({ raw }, { raw = it; true }, { 1_000L })
            assertEquals(DeviceLearningSession.RequestResult.AWAITING_OUTCOME,
                session().requestAndTrack("open Chrome", chrome) { true })
            assertTrue(DeviceLearningSession.isOutcome(phrase))
            val outcome = session().consume(phrase)!!
            assertTrue(outcome.accepted)
            assertTrue(outcome.pending.awaitingOutcome)
            assertFalse(outcome.needsClarification)
            assertEquals(chrome, outcome.pending.action)
            assertNull(session().consume(phrase))
        }
    }

    @Test fun failureFormsNeverBecomeSuccess() {
        for (phrase in listOf("It failed", "It doesn't work", "It doesn’t work!", "It isn't working", "The action did not work", "It didn't work")) {
            var raw: String? = null
            val session = DeviceLearningSession({ raw }, { raw = it; true })
            session.requestAndTrack("open Chrome", chrome) { true }
            val outcome = session.consume(phrase)!!
            assertFalse(outcome.accepted)
            assertFalse(outcome.needsClarification)
            assertNull(session.consume("it works"))
        }
    }

    @Test fun uncertainFeedbackKeepsOriginalPendingTargetWithoutPausingOrExtendingExpiry() {
        var raw: String? = null
        var saved = "[]"
        var now = 1_000L
        fun session() = DeviceLearningSession({ raw }, { raw = it; true }, { now })
        val commands = ValidatedCommandMemory({ saved }, { saved = it ?: "[]"; true })
        assertTrue(commands.recordSuccess("my app", "open_app", LearnableDeviceAction.capabilities, chrome.parameters))
        session().requestAndTrack("my app", chrome, replayed = true) { true }
        val original = raw
        for (phrase in listOf("The action did not fail", "I'm not sure", "Maybe it worked")) {
            val uncertain = session().consume(phrase)!!
            assertTrue(uncertain.needsClarification)
            assertFalse(uncertain.accepted)
            assertFalse(VerifiedFailureFeedback.pause(uncertain, commands))
            assertEquals(original, raw)
            assertFalse(commands.isPaused("my app"))
        }
        assertTrue(session().consume("it works")!!.accepted)
        assertNull(raw)
        session().requestAndTrack("my app", chrome) { true }
        now += 300_000
        assertTrue(session().consume("not sure")!!.needsClarification)
        now += 1
        assertNull(session().consume("it works"))
        assertNull(raw)
    }

    @Test fun outcomesCannotApproveUnrequestedActionsOrBecomeLearnedCommands() {
        var raw: String? = null
        var saved = "[]"
        val session = DeviceLearningSession({ raw }, { raw = it; true })
        val commands = ValidatedCommandMemory({ saved }, { saved = it ?: "[]"; true })
        for (phrase in listOf("it works", "it work", "the action did not fail", "it doesn't work")) {
            assertFalse(session.propose(DeviceLearningSession.Pending(phrase, chrome, true)))
            assertFalse(commands.recordSuccess(phrase, "open_app", LearnableDeviceAction.capabilities, chrome.parameters))
            assertNull(session.consume(phrase))
        }
        session.propose(DeviceLearningSession.Pending("open Chrome", chrome, true))
        assertNull(session.consume("it works"))
        assertNull(session.consume("yes"))
        for (phrase in listOf("don't say it worked", "what if it works", "it worked but open Maps", "it worked yesterday", "it didn't not work"))
            assertNull(OutcomeFeedback.parse(phrase))
    }

    @Test fun failedPendingClearCannotVerifyOutcome() {
        var raw: String? = null
        val writer = DeviceLearningSession({ raw }, { raw = it; true })
        writer.requestAndTrack("open Chrome", chrome) { true }
        val original = raw
        val reader = DeviceLearningSession({ raw }, { false })
        assertNull(reader.consume("it works"))
        assertEquals(original, raw)
    }
}
