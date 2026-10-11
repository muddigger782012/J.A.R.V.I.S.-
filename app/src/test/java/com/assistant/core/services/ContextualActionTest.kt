package com.assistant.core.services

import com.assistant.core.learning.ValidatedCommandMemory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContextualActionTest {
    private val chrome = LearnableDeviceAction("open_app", mapOf("package" to "com.android.chrome", "label" to "Chrome"))
    private val firefox = LearnableDeviceAction("open_app", mapOf("package" to "org.mozilla.firefox", "label" to "Firefox"))
    private val timer = LearnableDeviceAction("timer", mapOf("seconds" to "300"))

    @Test fun verifiedReferenceSurvivesRestartAndUsesChangedPreferenceOffline() {
        var saved = "{}"
        var pending: String? = null
        var aliases = "[]"
        fun memory() = AssistantMemory({ saved }, { saved = it; true })
        fun session() = DeviceLearningSession({ pending }, { pending = it; true })
        fun commands() = ValidatedCommandMemory({ aliases }, { aliases = it ?: "[]"; true })
        memory().savePreference("browser", chrome)
        val reference = ContextualAction.fromLesson(FallbackIntentLesson("OPEN_PREFERRED_APP", .95, mapOf("slot" to "browser"), learnable = true))!!
        assertTrue(session().propose(DeviceLearningSession.Pending("launch my usual browser", reference.resolve(memory())!!, true, contextual = reference)))
        val approval = session().consume("yes")!!
        assertEquals(chrome, approval.pending.action)
        assertEquals(0, commands().learnedPhraseCount())
        assertTrue(session().propose(approval.pending.copy(awaitingOutcome = true)))
        val outcome = session().consume("it worked")!!
        assertEquals(reference, outcome.pending.contextual)
        assertTrue(outcome.accepted)
        assertTrue(commands().recordSuccess(outcome.pending.phrase, reference.capabilityId, ContextualAction.capabilities, reference.parameters))
        memory().savePreference("browser", firefox)
        val learned = commands().match("launch my usual browser", ContextualAction.capabilities)!!
        assertEquals(firefox, ContextualAction(learned.capabilityId, learned.parameters).resolve(memory()))
        memory().clear()
        assertNull(ContextualAction(learned.capabilityId, learned.parameters).resolve(memory()))
    }

    @Test fun repeatedTimerResolvesCurrentVerifiedDurationAndNeverGuesses() {
        var saved = "{}"
        val memory = AssistantMemory({ saved }, { saved = it; true })
        val reference = ContextualAction("repeat_action", mapOf("kind" to "timer"))
        assertNull(reference.resolve(memory))
        memory.recordConfirmed(timer)
        assertEquals(timer, reference.resolve(memory))
        val newer = timer.copy(parameters = mapOf("seconds" to "600"))
        memory.recordConfirmed(newer)
        assertEquals(newer, reference.resolve(memory))
        assertNull(ContextualAction("repeat_action", mapOf("kind" to "anything")).resolve(memory))
    }

    @Test fun rejectsLowConfidenceExtraEntitiesUnsupportedSlotsAndCorruptPendingReferences() {
        fun lesson(entities: Map<String, String>, confidence: Double = .95) = FallbackIntentLesson("OPEN_PREFERRED_APP", confidence, entities, learnable = true)
        assertNull(ContextualAction.fromLesson(lesson(mapOf("slot" to "browser"), .89)))
        assertNull(ContextualAction.fromLesson(lesson(mapOf("slot" to "browser", "package" to "injected.app"))))
        assertNull(ContextualAction.fromLesson(lesson(mapOf("slot" to "email"))))
        var pending: String? = null
        val session = DeviceLearningSession({ pending }, { pending = it; true })
        assertFalse(session.propose(DeviceLearningSession.Pending("usual browser", chrome, true, contextual = ContextualAction("bad", emptyMap()))))
        assertTrue(session.propose(DeviceLearningSession.Pending("usual browser", chrome, true, contextual = ContextualAction("open_preferred", mapOf("slot" to "browser")))))
        assertFalse(session.consume("no")!!.accepted)
        assertNull(session.consume("it worked"))
    }

    @Test fun contextContainsOnlyApprovedLabelsAndConfirmedActionsWithoutPackages() {
        var saved = "{}"
        val memory = AssistantMemory({ saved }, { saved = it; true })
        val empty = JSONObject(ContextualAction.aiContext(memory))
        assertEquals(0, empty.getJSONObject("preferred_apps").length())
        memory.savePreference("browser", chrome)
        memory.recordConfirmed(timer)
        val data = JSONObject(ContextualAction.aiContext(memory))
        assertEquals("Chrome", data.getJSONObject("preferred_apps").getString("browser"))
        assertEquals("300", data.getJSONObject("confirmed_actions").getJSONObject("timer").getString("seconds"))
        assertFalse(data.toString().contains("com.android.chrome"))
        assertFalse(data.getJSONObject("confirmed_actions").has("open_app"))
    }
}
