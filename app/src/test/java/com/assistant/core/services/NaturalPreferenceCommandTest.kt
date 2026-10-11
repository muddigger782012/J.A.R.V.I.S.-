package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class NaturalPreferenceCommandTest {
    @Test fun screenshotAndNormalSettingVariantsHaveTheSameLocalMeaning() {
        val expected = AssistantMemoryCommands.Preference("browser", "Chrome")
        for (phrase in listOf("Set my preferred browser as Chrome", "Set my preferred browser to Chrome",
            "Remember my preferred browser is Chrome", "My preferred browser is Chrome",
            "Set preferred browser as Chrome", "Please set the preferred browser to Chrome.",
            "Make Chrome my preferred browser", "Set Chrome as my preferred browser")) {
            assertEquals(phrase, expected, AssistantMemoryCommands.preference(phrase))
        }
    }

    @Test fun missingMyAndPoliteOpeningVariantsUseSavedPreferenceBeforeAppLookup() {
        for (phrase in listOf("Open preferred browser", "Open my preferred browser", "Open the preferred browser",
            "Please open preferred browser", "Launch preferred browser please", "Use preferred browser")) {
            assertEquals(phrase, "browser", AssistantMemoryCommands.preferredSlot(phrase))
        }
        assertEquals("maps", AssistantMemoryCommands.preferredSlot("Open preferred navigation app"))
        assertEquals("music", AssistantMemoryCommands.preferredSlot("Open preferred music app"))
        assertEquals("preferred_browser", AssistantMemoryCommands.forgetKey("Forget preferred browser"))
    }

    @Test fun negationQuotingQuestionsAndUnclearSpeechDoNotBecomePreferenceActions() {
        for (phrase in listOf("Witnessing preferred browser", "Don't set my preferred browser as Chrome",
            "What does set my preferred browser as Chrome mean", "Say set my preferred browser as Chrome")) {
            assertNull(AssistantMemoryCommands.preference(phrase))
            assertNull(AssistantMemoryCommands.preferredSlot(phrase))
        }
        assertNull(AssistantMemoryCommands.preference("Set my preferred browser"))
        assertNull(AssistantMemoryCommands.preferredSlot("Don't open preferred browser"))
    }

    @Test fun screenshotSettingApprovalAndOpeningUseSamePersistedTargetOffline() {
        var saved = "{}"
        var pending: String? = null
        fun memory() = AssistantMemory({ saved }, { saved = it; true })
        fun confirmations() = PreferenceConfirmationStore({ pending }, { pending = it; true })
        val request = AssistantMemoryCommands.preference("Set my preferred browser as Chrome")!!
        val chrome = LearnableDeviceAction("open_app", mapOf("package" to "com.android.chrome", "label" to request.appName))
        assertTrue(confirmations().propose(request.slot, chrome))
        assertNull(memory().preferred("browser"))
        val approval = confirmations().consume("yes")!!
        assertTrue(approval.approved)
        assertTrue(memory().savePreference(approval.slot, approval.action!!))
        assertEquals(chrome, memory().preferred(AssistantMemoryCommands.preferredSlot("Open preferred browser")!!))
    }
}
