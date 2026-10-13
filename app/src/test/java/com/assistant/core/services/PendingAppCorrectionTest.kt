package com.assistant.core.services

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PendingAppCorrectionTest {
    private val chrome = LearnableDeviceAction("open_app", mapOf("package" to "com.android.chrome", "label" to "Chrome"))
    private val firefox = LearnableDeviceAction("open_app", mapOf("package" to "org.mozilla.firefox", "label" to "Firefox"))
    private val resolve: (String) -> Pair<String, String>? = { label ->
        if (label.equals("Firefox", true)) "org.mozilla.firefox" to "Firefox" else null
    }

    @Test fun explicitAliasEditPersistsHostResolvedTargetBeforeFreshApprovalAndOutcome() {
        var raw: String? = null
        var now = 1000L
        fun session() = DeviceLearningSession({ raw }, { raw = it; true }, { now })
        assertTrue(session().propose(DeviceLearningSession.Pending("desk app", chrome, true, explicitTeaching = true)))
        val created = JSONObject(raw!!).getLong("created")
        now += 500
        val edit = session().consume("Actually, use Firefox instead!", resolve)!!
        assertEquals(DeviceLearningSession.Correction.UPDATED, edit.correction)
        assertFalse(edit.accepted); assertFalse(edit.pending.awaitingOutcome)
        assertEquals(firefox, edit.pending.action); assertEquals("desk app", edit.pending.phrase)
        assertTrue(edit.pending.shouldLearn(false)); assertEquals(created, JSONObject(raw!!).getLong("created"))
        val reminder = session().consume("what am I approving")!!
        assertEquals(firefox, reminder.pending.action)
        val approved = session().consume("yes")!!
        assertTrue(approved.accepted); assertEquals(firefox, approved.pending.action)
        assertTrue(session().propose(approved.pending.copy(awaitingOutcome = true)))
        val outcome = session().consume("actually it worked")!!
        assertTrue(outcome.accepted); assertEquals(firefox, outcome.pending.action)
        assertEquals("desk app", outcome.pending.phrase); assertTrue(outcome.pending.shouldLearn(false))
        assertNull(session().consume("yes"))
    }

    @Test fun ordinaryRequestWordingIsNotLearnedAsADifferentAppAndExpiryDoesNotMove() {
        var raw: String? = null
        var now = 1000L
        val session = DeviceLearningSession({ raw }, { raw = it; true }, { now })
        session.propose(DeviceLearningSession.Pending("open Chrome", chrome, true))
        val edited = session.consume("open Firefox instead", resolve)!!
        assertEquals(firefox, edited.pending.action); assertFalse(edited.pending.shouldLearn(true))
        now += 300_000
        assertEquals(DeviceLearningSession.Correction.UPDATED, session.consume("use Firefox instead", resolve)!!.correction)
        now += 1
        assertNull(session.consume("yes")); assertNull(raw)
    }

    @Test fun invalidOrAmbiguousAppDoesNotReplaceProposalAndFailedStorageKeepsOldTarget() {
        var raw: String? = null
        val session = DeviceLearningSession({ raw }, { raw = it; true })
        session.propose(DeviceLearningSession.Pending("desk app", chrome, true))
        val original = raw
        val invalidResolvers: List<(String) -> Pair<String, String>?> = listOf({ null }, { "org.mozilla.firefox" to "Wrong label" }, { throw IllegalStateException("Unavailable") })
        for (resolver in invalidResolvers) {
            val edit = session.consume("use Firefox instead", resolver)!!
            assertEquals(DeviceLearningSession.Correction.INVALID_APP, edit.correction)
            assertFalse(edit.accepted); assertEquals(chrome, edit.pending.action); assertEquals(original, raw)
        }
        val failed = DeviceLearningSession({ raw }, { false }).consume("use Firefox instead", resolve)!!
        assertEquals(DeviceLearningSession.Correction.SAVE_FAILED, failed.correction)
        assertEquals(original, raw); assertEquals(chrome, session.consume("yes")!!.pending.action)
    }

    @Test fun requestedCrossKindAndContextualActionsAreLockedWithoutResolvingAnotherApp() {
        var raw: String? = null
        val session = DeviceLearningSession({ raw }, { raw = it; true })
        val locked = listOf(
            DeviceLearningSession.Pending("desk app", chrome, false, awaitingOutcome = true),
            DeviceLearningSession.Pending("timer alias", LearnableDeviceAction("timer", mapOf("seconds" to "300")), true),
            DeviceLearningSession.Pending("usual browser", chrome, true, contextual = ContextualAction("open_preferred", mapOf("slot" to "browser")))
        )
        for (proposal in locked) {
            assertTrue(session.propose(proposal)); val original = raw
            val edit = session.consume("use Firefox instead") { fail("locked targets must not resolve a replacement"); null }!!
            assertEquals(if (proposal.awaitingOutcome) DeviceLearningSession.Correction.ALREADY_REQUESTED else DeviceLearningSession.Correction.TARGET_LOCKED, edit.correction)
            assertFalse(edit.accepted); assertEquals(original, raw)
        }
        assertNull(session.consume("open Maps")); assertNull(session.consume("yes"))
        assertNull(session.consume("use Firefox instead", resolve))
    }

    @Test fun boundedCorrectionsAreReservedAndNeverInterpretNegationOrExtraActions() {
        for (text in listOf("don't use Firefox instead", "explain use Firefox instead", "use Firefox instead and open Chrome", "use Firefox instead yesterday")) {
            assertFalse(PendingAppCorrection.isRequest(text)); assertNull(PendingAppCorrection.action(text, resolve))
        }
        assertNull(PendingAppCorrection.action("use Firefox and Chrome instead", resolve))
        assertTrue(LearnedCommandReviewCommands.isReserved("use Firefox instead"))
        val session = DeviceLearningSession({ null }, { true })
        assertFalse(session.propose(DeviceLearningSession.Pending("use Firefox instead", chrome, true)))
    }
}
