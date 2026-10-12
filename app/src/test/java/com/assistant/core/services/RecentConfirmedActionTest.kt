package com.assistant.core.services

import com.assistant.core.learning.ValidatedCommandMemory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RecentConfirmedActionTest {
    private val timer = LearnableDeviceAction("timer", mapOf("seconds" to "300"))
    private val chrome = LearnableDeviceAction("open_app", mapOf("package" to "com.android.chrome", "label" to "Chrome"))
    private val recent = ContextualAction("repeat_action", mapOf("kind" to "most_recent"))

    @Test fun orderSurvivesRestartAndPreferenceChangesDoNotChangeIt() {
        var saved = "{}"
        fun memory() = AssistantMemory({ saved }, { saved = it; true })
        assertNull(recent.resolve(memory()))
        assertTrue(memory().recordConfirmed(timer))
        assertEquals(timer, recent.resolve(memory()))
        assertTrue(memory().recordConfirmed(chrome))
        assertEquals(chrome, recent.resolve(memory()))
        assertTrue(memory().savePreference("browser", chrome))
        assertEquals(chrome, recent.resolve(memory()))
        assertTrue(memory().recordConfirmed(timer))
        assertEquals(timer, recent.resolve(memory()))
        val context = JSONObject(ContextualAction.aiContext(memory()))
        assertEquals("timer", context.getJSONObject("confirmed_actions").getJSONObject("most_recent").getString("kind"))
        assertFalse(context.toString().contains("com.android.chrome"))
    }

    @Test fun oldSlotsAreNotAssumedToHaveAnOrderAndForgettingDoesNotResurrectOlderAction() {
        var saved = "{}"
        val memory = AssistantMemory({ saved }, { saved = it; true })
        assertTrue(memory.seedRecentOnce(timer, chrome))
        assertNull(recent.resolve(memory))
        assertTrue(memory.recordConfirmed(chrome))
        assertTrue(memory.recordConfirmed(timer))
        assertTrue(memory.forget("last_open_app"))
        assertEquals(timer, recent.resolve(memory))
        assertTrue(memory.forget("last_timer"))
        assertNull(recent.resolve(memory))
        assertTrue(memory.recordConfirmed(chrome))
        assertTrue(memory.forget(AssistantMemoryCommands.forgetKey("forget the last action")!!))
        assertNull(recent.resolve(memory))
        assertEquals(chrome, memory.lastConfirmed("open_app"))
        assertTrue(memory.clear())
        assertNull(recent.resolve(memory))
    }

    @Test fun approvalAndRequestedActionsDoNotBecomeVerifiedUntilSuccessfulOutcome() {
        var saved = "{}"
        var pending: String? = null
        fun memory() = AssistantMemory({ saved }, { saved = it; true })
        fun session() = DeviceLearningSession({ pending }, { pending = it; true })
        memory().recordConfirmed(timer)
        assertTrue(session().propose(DeviceLearningSession.Pending("do that again", timer, false, contextual = recent)))
        val approval = session().consume("yes")!!
        assertTrue(approval.accepted)
        assertFalse(approval.pending.awaitingOutcome)
        assertEquals(DeviceLearningSession.RequestResult.AWAITING_OUTCOME,
            session().requestAndTrack("open Chrome", chrome) { true })
        assertEquals(timer, recent.resolve(memory()))
        assertFalse(session().consume("it failed")!!.accepted)
        assertEquals(timer, recent.resolve(memory()))
        session().requestAndTrack("open Chrome", chrome) { true }
        val outcome = session().consume("it worked")!!
        assertTrue(outcome.accepted && outcome.pending.awaitingOutcome)
        assertTrue(memory().recordConfirmed(outcome.pending.action))
        assertEquals(chrome, recent.resolve(memory()))
    }

    @Test fun learnedGenericReferenceKeepsItsMeaningAsCurrentActionChanges() {
        var saved = "{}"
        var aliases = "[]"
        val memory = AssistantMemory({ saved }, { saved = it; true })
        fun commands() = ValidatedCommandMemory({ aliases }, { aliases = it ?: "[]"; true })
        val lesson = FallbackIntentLesson("REPEAT_ACTION", .95, mapOf("kind" to "most_recent"), learnable = true)
        assertEquals(recent, ContextualAction.fromLesson(lesson))
        assertTrue(commands().recordSuccess("one more time", recent.capabilityId, ContextualAction.capabilities, recent.parameters))
        val entry = commands().match("one more time", ContextualAction.capabilities)!!
        val restored = ContextualAction(entry.capabilityId, entry.parameters)
        memory.recordConfirmed(timer)
        assertEquals(timer, restored.resolve(memory))
        memory.recordConfirmed(chrome)
        assertEquals(chrome, restored.resolve(memory))
        memory.clear()
        assertNull(restored.resolve(memory))
    }

    @Test fun missingCorruptOrFailedStorageCannotInventAnOrderedAction() {
        assertNull(AssistantMemory({ """{"last_action_kind":"timer","last_timer":{"capability":"open_app","parameters":{"package":"com.android.chrome","label":"Chrome"}}}""" }, { true }).mostRecentConfirmed())
        assertNull(AssistantMemory({ """{"last_action_kind":"shell"}""" }, { true }).mostRecentConfirmed())
        val failed = AssistantMemory({ "{}" }, { false })
        assertFalse(failed.recordConfirmed(timer))
        assertNull(failed.mostRecentConfirmed())
        assertNull(ContextualAction.fromLesson(FallbackIntentLesson("REPEAT_ACTION", .89, mapOf("kind" to "most_recent"), learnable = true)))
        for (phrase in listOf("don't do that again", "what does repeat that mean", "do that again and send a message"))
            assertNull(AssistantMemoryCommands.repeatCapability(phrase))
        for (phrase in listOf("Do that again!", "Please do that again", "repeat the last confirmed action", "repeat that"))
            assertEquals("most_recent", AssistantMemoryCommands.repeatCapability(phrase))
    }
}
