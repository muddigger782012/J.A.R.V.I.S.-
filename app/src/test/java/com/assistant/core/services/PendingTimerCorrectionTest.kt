package com.assistant.core.services

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PendingTimerCorrectionTest {
    private val timer = LearnableDeviceAction("timer", mapOf("seconds" to "300"))
    private val chrome = LearnableDeviceAction("open_app", mapOf("package" to "com.android.chrome", "label" to "Chrome"))

    @Test fun editRequiresFreshApprovalPreservesExpiryAndAliasConsentAcrossRestart() {
        var raw: String? = null
        var now = 1000L
        fun session() = DeviceLearningSession({ raw }, { raw = it; true }, { now })
        assertTrue(session().propose(DeviceLearningSession.Pending("coffee break", timer, true, explicitTeaching = true)))
        val created = JSONObject(raw!!).getLong("created")
        now += 500
        val edit = session().consume("Actually, make that 10 minutes!")!!
        assertEquals(DeviceLearningSession.Correction.UPDATED, edit.correction)
        assertFalse(edit.accepted); assertTrue(edit.needsClarification); assertFalse(edit.pending.awaitingOutcome)
        assertEquals("600", edit.pending.action.parameters["seconds"])
        assertEquals(created, JSONObject(raw!!).getLong("created"))
        assertEquals("coffee break", edit.pending.phrase); assertTrue(edit.pending.shouldLearn(false))
        val approval = session().consume("yes")!!
        assertTrue(approval.accepted); assertEquals("600", approval.pending.action.parameters["seconds"])
        assertTrue(session().propose(approval.pending.copy(awaitingOutcome = true)))
        val outcome = session().consume("it worked")!!
        assertTrue(outcome.accepted); assertEquals("coffee break", outcome.pending.phrase)
        assertTrue(outcome.pending.shouldLearn(false))
        assertNull(session().consume("yes"))
        assertTrue(session().propose(DeviceLearningSession.Pending("coffee break", timer, true, explicitTeaching = true)))
        now += 300_000
        assertEquals(DeviceLearningSession.Correction.UPDATED, session().consume("make that 1 minute")!!.correction)
        now += 1
        assertNull(session().consume("yes")); assertNull(raw)
    }

    @Test fun ordinaryDurationWordingCannotBeLearnedWithAnEditedMeaning() {
        var raw: String? = null
        val session = DeviceLearningSession({ raw }, { raw = it; true })
        session.propose(DeviceLearningSession.Pending("give me 5 minutes", timer, true))
        val edit = session.consume("make it 10 minutes")!!
        assertEquals(DeviceLearningSession.Correction.UPDATED, edit.correction)
        assertFalse(edit.pending.shouldLearn(true)); assertFalse(edit.pending.explicitTeaching)
        assertTrue(session.consume("yes")!!.accepted)
    }

    @Test fun invalidLockedRequestedAndFailedStorageEditsRetainOriginalTarget() {
        var raw: String? = null
        val session = DeviceLearningSession({ raw }, { raw = it; true })
        fun check(pending: DeviceLearningSession.Pending, text: String, expected: DeviceLearningSession.Correction) {
            assertTrue(session.propose(pending))
            val original = raw
            val edit = session.consume(text)!!
            assertEquals(expected, edit.correction); assertFalse(edit.accepted)
            assertEquals(pending.action, edit.pending.action); assertEquals(original, raw)
        }
        for (text in listOf("make that 0 minutes", "make that 25 hours", "make it -1 minute", "make it 1.5 minutes", "make that five hundred hours"))
            check(DeviceLearningSession.Pending("timer alias", timer, true), text, DeviceLearningSession.Correction.INVALID_DURATION)
        check(DeviceLearningSession.Pending("app alias", chrome, true), "make that 10 minutes", DeviceLearningSession.Correction.TARGET_LOCKED)
        check(DeviceLearningSession.Pending("repeat timer", timer, true, contextual = ContextualAction("repeat_action", mapOf("kind" to "timer"))),
            "make that 10 minutes", DeviceLearningSession.Correction.TARGET_LOCKED)
        check(DeviceLearningSession.Pending("timer alias", timer, false, awaitingOutcome = true), "make that 10 minutes", DeviceLearningSession.Correction.ALREADY_REQUESTED)
        assertTrue(session.consume("it worked")!!.accepted)
        session.propose(DeviceLearningSession.Pending("timer alias", timer, true))
        val original = raw
        val failed = DeviceLearningSession({ raw }, { false }).consume("make that 10 minutes")!!
        assertEquals(DeviceLearningSession.Correction.SAVE_FAILED, failed.correction); assertEquals(original, raw)
        assertEquals("300", session.consume("yes")!!.pending.action.parameters["seconds"])
    }

    @Test fun boundedGrammarCannotBecomeAnAliasOrAuthorizeWithoutContext() {
        assertEquals("86400", PendingTimerCorrection.action("make that 24 hours")!!.parameters["seconds"])
        for (text in listOf("don't make that 10 minutes", "explain make that 10 minutes", "make that 10 minutes and open Chrome", "make that 10 minutes yesterday")) {
            assertFalse(PendingTimerCorrection.isRequest(text)); assertNull(PendingTimerCorrection.action(text))
        }
        var raw: String? = null
        val session = DeviceLearningSession({ raw }, { raw = it; true })
        assertNull(session.consume("make that 10 minutes"))
        assertFalse(session.propose(DeviceLearningSession.Pending("make that 10 minutes", timer, true)))
        assertTrue(LearnedCommandReviewCommands.isReserved("make that 10 minutes"))
        session.propose(DeviceLearningSession.Pending("timer alias", timer, true))
        assertNull(session.consume("make that 10 minutes and open Chrome")); assertNull(session.consume("yes"))
    }
}
