package com.assistant.core.services

import com.assistant.core.learning.ValidatedCommandMemory
import org.junit.Assert.*
import org.junit.Test

class DeviceLearningSessionTest {
    private val timer = LearnableDeviceAction("timer", mapOf("seconds" to "300"))
    private val app = LearnableDeviceAction("open_app", mapOf("package" to "com.android.chrome", "label" to "Chrome"))

    @Test fun timerAndAppRequireOutcomeBeforePersistentOfflineReuse() {
        for (action in listOf(timer, app)) {
            var pending: String? = null
            var saved = "[]"
            fun session() = DeviceLearningSession({ pending }, { pending = it; true }, { 1_000L })
            fun memory() = ValidatedCommandMemory({ saved }, { saved = it ?: "[]"; true })
            val proposal = DeviceLearningSession.Pending("my custom phrase", action, true)
            assertTrue(session().propose(proposal))
            val approval = session().consume("yes")!!
            assertTrue(approval.accepted)
            assertFalse(approval.pending.awaitingOutcome)
            // Requesting the action alone is not enough to learn it.
            assertEquals(0, memory().learnedPhraseCount())
            assertTrue(session().propose(approval.pending.copy(awaitingOutcome = true)))
            assertEquals(0, memory().learnedPhraseCount())
            val outcome = session().consume("It worked!")!!
            assertTrue(outcome.pending.awaitingOutcome)
            assertTrue(outcome.accepted)
            assertTrue(memory().recordSuccess(outcome.pending.phrase, action.capabilityId, LearnableDeviceAction.capabilities, action.parameters))
            val offline = memory().match("my custom phrase", LearnableDeviceAction.capabilities)!!
            assertEquals(action, LearnableDeviceAction(offline.capabilityId, offline.parameters))
            assertNull(memory().match("yes", LearnableDeviceAction.capabilities))
            assertNull(memory().match("it worked", LearnableDeviceAction.capabilities))
            assertNull(session().consume("it worked"))
        }
    }

    @Test fun declinedFailedExpiredOrUnrelatedTurnsNeverApproveLearning() {
        var pending: String? = null
        var now = 1_000L
        val session = DeviceLearningSession({ pending }, { pending = it; true }, { now })
        val proposal = DeviceLearningSession.Pending("my timer phrase", timer, true)
        assertTrue(session.propose(proposal))
        assertFalse(session.consume("no")!!.accepted)
        assertTrue(session.propose(proposal.copy(awaitingOutcome = true)))
        assertFalse(session.consume("it didn't work")!!.accepted)
        assertTrue(session.propose(proposal.copy(awaitingOutcome = true)))
        assertNull(session.consume("yes"))
        assertNull(session.consume("it worked"))
        assertTrue(session.propose(proposal))
        assertNull(session.consume("what time is it"))
        assertNull(session.consume("yes"))
        assertTrue(session.propose(proposal))
        now += 300_001
        assertNull(session.consume("yes"))
    }

    @Test fun parameterConflictsCannotReplaceAnExistingMeaning() {
        var saved = "[]"
        val memory = ValidatedCommandMemory({ saved }, { saved = it ?: "[]"; true })
        assertTrue(memory.recordSuccess("give me a break", "timer", LearnableDeviceAction.capabilities, timer.parameters))
        assertFalse(memory.recordSuccess("give me a break", "timer", LearnableDeviceAction.capabilities, mapOf("seconds" to "600")))
        assertEquals(timer.parameters, memory.match("give me a break", LearnableDeviceAction.capabilities)?.parameters)
    }

    @Test fun strictLessonsRejectMissingDurationsUnknownActionsAndAmbiguousApps() {
        fun lesson(intent: String, entities: Map<String, String>, confidence: Double = 0.95) = FallbackIntentLesson(intent, confidence, entities, learnable = true)
        assertEquals(timer, LearnableDeviceAction.fromLesson(lesson("SET_TIMER", timer.parameters)) { null })
        for (seconds in listOf("0", "-1", "86401", "999999999999", "five", "300;command")) {
            assertNull(LearnableDeviceAction.fromLesson(lesson("SET_TIMER", mapOf("seconds" to seconds))) { null })
        }
        assertNull(LearnableDeviceAction.fromLesson(lesson("SET_TIMER", emptyMap())) { null })
        assertNull(LearnableDeviceAction.fromLesson(lesson("SET_TIMER", timer.parameters, 0.89)) { null })
        assertNull(LearnableDeviceAction.fromLesson(lesson("CALL_CONTACT", mapOf("name" to "Someone"))) { null })
        assertNull(LearnableDeviceAction.fromLesson(lesson("OPEN_APP", mapOf("app_name" to "Maps"))) { null })
        assertEquals(app, LearnableDeviceAction.fromLesson(lesson("OPEN_APP", mapOf("app_name" to "Chrome"))) { "com.android.chrome" to "Chrome" })
        assertNull(LearnableDeviceAction.fromLesson(lesson("OPEN_APP", mapOf("app_name" to "Chrome", "package" to "injected.app"))) { "com.android.chrome" to "Chrome" })
    }

    @Test fun failedStorageAndInvalidOrConfirmationOnlyProposalsAreRejected() {
        val session = DeviceLearningSession({ null }, { false })
        assertFalse(session.propose(DeviceLearningSession.Pending("my phrase", timer, true)))
        val normal = DeviceLearningSession({ null }, { true })
        assertFalse(normal.propose(DeviceLearningSession.Pending("yes", timer, true)))
        assertFalse(normal.propose(DeviceLearningSession.Pending("it worked", timer, true)))
        assertFalse(normal.propose(DeviceLearningSession.Pending("my phrase", timer.copy(parameters = emptyMap()), true)))
    }
}
