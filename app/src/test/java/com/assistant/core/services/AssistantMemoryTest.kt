package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class AssistantMemoryTest {
    private val timer = LearnableDeviceAction("timer", mapOf("seconds" to "300"))
    private val chrome = LearnableDeviceAction("open_app", mapOf("package" to "com.android.chrome", "label" to "Chrome"))

    @Test fun approvedPreferenceAndConfirmedContextSurviveInstanceRecreation() {
        var saved = "{}"
        fun memory() = AssistantMemory({ saved }, { saved = it; true })
        assertTrue(memory().savePreference("browser", chrome))
        assertTrue(memory().recordConfirmed(timer))
        assertTrue(memory().recordConfirmed(chrome))
        assertEquals(chrome, memory().preferred("browser"))
        assertEquals(timer, memory().lastConfirmed("timer"))
        assertEquals(chrome, memory().lastConfirmed("open_app"))
        assertTrue(memory().summary().contains("Chrome"))
        assertFalse(memory().savePreference("browser", timer))
        assertFalse(memory().savePreference("unknown", chrome))
    }

    @Test fun importedContextIsNotResurrectedAfterClearOrForget() {
        var saved = "{}"
        val memory = AssistantMemory({ saved }, { saved = it; true })
        assertTrue(memory.seedRecentOnce(timer, chrome))
        assertEquals(timer, memory.lastConfirmed("timer"))
        assertTrue(memory.forget("last_timer"))
        assertTrue(memory.seedRecentOnce(timer, chrome))
        assertNull(memory.lastConfirmed("timer"))
        assertTrue(memory.savePreference("browser", chrome))
        assertTrue(memory.clear())
        assertTrue(memory.seedRecentOnce(timer, chrome))
        assertNull(memory.lastConfirmed("open_app"))
        assertNull(memory.preferred("browser"))
    }

    @Test fun existingRecentContextWinsOverMigration() {
        var saved = "{}"
        val memory = AssistantMemory({ saved }, { saved = it; true })
        val newer = timer.copy(parameters = mapOf("seconds" to "600"))
        assertTrue(memory.recordConfirmed(newer))
        assertTrue(memory.seedRecentOnce(timer, chrome))
        assertEquals(newer, memory.lastConfirmed("timer"))
    }

    @Test fun failedWritesAndCorruptDataDoNotInventMemory() {
        val failed = AssistantMemory({ "{}" }, { false })
        assertFalse(failed.savePreference("browser", chrome))
        assertFalse(failed.recordConfirmed(timer))
        assertFalse(failed.clear())
        assertNull(AssistantMemory({ "not json" }, { true }).preferred("browser"))
        assertNull(AssistantMemory({ """{"last_timer":{"capability":"timer","parameters":{"seconds":"-1"}}}""" }, { true }).lastConfirmed("timer"))
    }

    @Test fun commandsResolveExplicitSlotsAndTypedFollowupsWithoutCloud() {
        assertEquals(AssistantMemoryCommands.Preference("browser", "Chrome"), AssistantMemoryCommands.preference("Remember my preferred browser is Chrome."))
        assertEquals("browser", AssistantMemoryCommands.preferredSlot("Open my preferred browser!"))
        assertEquals("maps", AssistantMemoryCommands.preferredSlot("use my preferred maps app"))
        assertEquals("timer", AssistantMemoryCommands.repeatCapability("Use the same timer again."))
        assertEquals("open_app", AssistantMemoryCommands.repeatCapability("Open the same app again"))
        assertEquals("preferred_music", AssistantMemoryCommands.forgetKey("forget my preferred music app"))
        assertEquals("last_timer", AssistantMemoryCommands.forgetKey("forget the last timer"))
        assertNull(AssistantMemoryCommands.repeatCapability("do that again"))
        assertNull(AssistantMemoryCommands.preferredSlot("don't open my preferred browser"))
    }

    @Test fun preferenceApprovalIsOneShotAndExpiresOrCancelsOnUnrelatedInput() {
        var pending: String? = null
        var now = 1_000L
        fun confirmations() = PreferenceConfirmationStore({ pending }, { pending = it; true }, { now })
        assertTrue(confirmations().propose("browser", chrome))
        val approved = confirmations().consume("yes")!!
        assertTrue(approved.approved)
        assertEquals(chrome, approved.action)
        assertNull(confirmations().consume("yes"))
        assertTrue(confirmations().propose("browser", chrome))
        assertFalse(confirmations().consume("no")!!.approved)
        assertTrue(confirmations().propose("clear"))
        assertNull(confirmations().consume("what time is it"))
        assertNull(confirmations().consume("yes"))
        assertTrue(confirmations().propose("clear"))
        now += 120_001
        assertNull(confirmations().consume("yes"))
    }
}
