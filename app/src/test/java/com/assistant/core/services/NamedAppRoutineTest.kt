package com.assistant.core.services

import com.assistant.core.learning.ApprovedRoutineRegistry
import org.junit.Assert.*
import org.junit.Test

class NamedAppRoutineTest {
    private val chrome = LearnableDeviceAction("open_app", mapOf("package" to "com.android.chrome", "label" to "Chrome"))
    private fun lesson(steps: String) = FallbackIntentLesson("CREATE_ROUTINE", .95,
        mapOf("phrase" to "chrome time", "steps" to steps), learnable = true)
    private fun resolve(name: String): Pair<String, String>? =
        if (name.equals("Chrome", true)) "com.android.chrome" to "Chrome" else null

    @Test fun aiDraftBindsLocallyAndApprovalPersistsWithoutRunning() {
        val proposal = RoutineProposal.fromAiLesson(lesson("volume_down,open:Chrome"), ::resolve)!!
        assertEquals(chrome, RoutineSteps.appAction(proposal.actions.last()))
        assertEquals("open Chrome", RoutineProposal.describe(proposal.actions.last()))
        var pending: String? = null
        var stored = "[]"
        val confirmations = RoutineConfirmationStore({ pending }, { pending = it; true })
        assertTrue(confirmations.propose(proposal))
        val registry = ApprovedRoutineRegistry({ stored }, { stored = it; true })
        assertNull(registry.find("chrome time"))
        assertTrue(registry.saveApproved(confirmations.consume("confirm routine")!!.routine))
        assertEquals(proposal, registry.find("chrome time"))
        assertTrue(registry.listSummaries().single().contains("open Chrome"))
        assertFalse(registry.listSummaries().single().contains("com.android.chrome"))
        assertNull(confirmations.consume("confirm routine"))
    }

    @Test fun rejectsAiBindingsAmbiguousAppsAndInvalidOrders() {
        assertNull(RoutineProposal.fromAiLesson(lesson(RoutineSteps.appStep(chrome)!!), ::resolve))
        assertNull(RoutineProposal.fromAiLesson(lesson("open:Chrome")) { null })
        assertNull(RoutineProposal.fromAiLesson(lesson("open:Chrome")) { "com.bad.app" to "Different" })
        assertNull(RoutineProposal.fromAiLesson(lesson("open:Missing"), ::resolve))
        assertNull(RoutineProposal.fromAiLesson(lesson("open:Chrome").copy(confidence = .89), ::resolve))
        for (steps in listOf("open:Chrome,volume_down", "timer:300,open:Chrome", "open:Chrome,preferred:browser",
            "app_update,open:Chrome", "open:Chrome,open:chrome", "open:", "open:Chrome,call:someone"))
            assertNull(RoutineProposal.fromAiLesson(lesson(steps), ::resolve))
        assertNull(RoutineSteps.appAction("app:com.android.chrome:%ZZ"))
        assertNull(RoutineSteps.appAction("app:com.android.chrome:Chrome:extra"))
        val punctuated = chrome.copy(parameters = chrome.parameters + ("label" to "Maps, Travel: A+B"))
        assertEquals(punctuated, RoutineSteps.appAction(RoutineSteps.appStep(punctuated)!!))
    }

    @Test fun missingBoundAppStopsBeforeAnySideEffectAndNeverSwitchesToPreference() {
        val steps = RoutineProposal.fromAiLesson(lesson("volume_down,open:Chrome"), ::resolve)!!.actions
        var saved = "{}"
        val memory = AssistantMemory({ saved }, { saved = it; true })
        memory.savePreference("browser", LearnableDeviceAction("open_app", mapOf("package" to "org.mozilla.firefox", "label" to "Firefox")))
        var calls = 0
        val blocked = RoutineExecution.runWithPreferred(steps, memory, { it == "org.mozilla.firefox" }) { _, _ ->
            calls++; RoutineExecution.Status.VERIFIED
        }
        assertTrue(blocked.unavailableApp)
        assertEquals(0, calls)
        var launched: LearnableDeviceAction? = null
        val result = RoutineExecution.runWithPreferred(steps, memory, { it == "com.android.chrome" }) { step, target ->
            if (step == "volume_down") RoutineExecution.Status.VERIFIED
            else { launched = target; RoutineExecution.Status.AWAITING_OUTCOME }
        }
        assertEquals(chrome, launched)
        assertTrue(result.awaitingOutcome)
        assertNull(memory.mostRecentConfirmed())
    }
}
