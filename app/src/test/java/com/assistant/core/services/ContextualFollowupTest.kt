package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class ContextualFollowupTest {
    private val chrome = LearnableDeviceAction("open_app", mapOf("package" to "com.android.chrome", "label" to "Chrome"))
    private val timer = LearnableDeviceAction("timer", mapOf("seconds" to "300"))

    @Test fun exactOnlineAndOfflineScreenshotPhrasesResolveLocally() {
        for (phrase in listOf("Use that same timer again", "Use the last confirmed timer", "Use the same timer again"))
            assertEquals("timer", AssistantMemoryCommands.repeatCapability(phrase))
        for (phrase in listOf("Open that same app again", "Open the same app again", "Open the last confirmed app"))
            assertEquals("open_app", AssistantMemoryCommands.repeatCapability(phrase))
        for (phrase in listOf("Open the same browser again", "Open that same browser again", "Open my preferred browser"))
            assertEquals("browser", AssistantMemoryCommands.preferredSlot(phrase))
        assertNull(AssistantMemoryCommands.repeatCapability("Don't use that same timer again"))
        assertNull(AssistantMemoryCommands.preferredSlot("Don't open the same browser again"))
        assertNull(AssistantMemoryCommands.repeatCapability("What does use the same timer again mean"))
    }

    @Test fun preferredBrowserLaunchOutcomeBecomesLastConfirmedAppAndSurvivesRestart() {
        var saved = "{}"
        var pending: String? = null
        fun memory() = AssistantMemory({ saved }, { saved = it; true })
        fun session() = DeviceLearningSession({ pending }, { pending = it; true })
        assertTrue(memory().savePreference("browser", chrome))
        val selected = memory().preferred(AssistantMemoryCommands.preferredSlot("Open my preferred browser")!!)!!
        assertEquals(DeviceLearningSession.RequestResult.AWAITING_OUTCOME,
            session().requestAndTrack("Open my preferred browser", selected) { true })
        assertNull(memory().lastConfirmed("open_app"))
        val outcome = session().consume("It worked")!!
        assertTrue(outcome.accepted)
        assertFalse(outcome.pending.learnable)
        assertTrue(outcome.pending.awaitingOutcome)
        assertTrue(memory().recordConfirmed(outcome.pending.action))
        val repeated = memory().lastConfirmed(AssistantMemoryCommands.repeatCapability("Open that same app again")!!)
        assertEquals(chrome, repeated)
        assertNull(session().consume("it worked"))
    }

    @Test fun screenshotTimerMemoryCanBeReusedWithoutAProviderOrLiteralAppLookup() {
        var saved = "{}"
        fun memory() = AssistantMemory({ saved }, { saved = it; true })
        assertTrue(memory().recordConfirmed(timer))
        for (phrase in listOf("Use that same timer again", "Use the last confirmed timer")) {
            assertEquals(timer, memory().lastConfirmed(AssistantMemoryCommands.repeatCapability(phrase)!!))
        }
    }

    @Test fun failedLaunchOrOutcomeDoesNotReplacePreviousConfirmedContext() {
        var saved = "{}"
        var pending: String? = null
        val memory = AssistantMemory({ saved }, { saved = it; true })
        val session = DeviceLearningSession({ pending }, { pending = it; true })
        assertTrue(memory.recordConfirmed(timer))
        assertEquals(DeviceLearningSession.RequestResult.FAILED, session.requestAndTrack("open Chrome", chrome) { false })
        assertNull(session.consume("it worked"))
        assertEquals(DeviceLearningSession.RequestResult.AWAITING_OUTCOME, session.requestAndTrack("open Chrome", chrome) { true })
        assertFalse(session.consume("it failed")!!.accepted)
        assertNull(memory.lastConfirmed("open_app"))
        assertEquals(timer, memory.lastConfirmed("timer"))
    }

    @Test fun outcomeTrackingFailureDoesNotClaimContextWasSaved() {
        val session = DeviceLearningSession({ null }, { false })
        assertEquals(DeviceLearningSession.RequestResult.REQUESTED_WITHOUT_TRACKING,
            session.requestAndTrack("open Chrome", chrome) { true })
    }
}
