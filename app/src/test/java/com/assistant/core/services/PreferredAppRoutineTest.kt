package com.assistant.core.services

import com.assistant.core.learning.ApprovedRoutineRegistry
import org.junit.Assert.*
import org.junit.Test

class PreferredAppRoutineTest {
    private val chrome = LearnableDeviceAction("open_app", mapOf("package" to "com.android.chrome", "label" to "Chrome"))
    private val firefox = LearnableDeviceAction("open_app", mapOf("package" to "org.mozilla.firefox", "label" to "Firefox"))
    private val steps = listOf("volume_down", "preferred:browser")

    @Test fun approvedOfflineRoutineUsesCurrentPreferenceRatherThanFrozenApp() {
        var saved = "{}"
        var routines = "[]"
        fun memory() = AssistantMemory({ saved }, { saved = it; true })
        fun registry() = ApprovedRoutineRegistry({ routines }, { routines = it; true })
        val proposal = RoutineProposal.fromLesson(FallbackIntentLesson("CREATE_ROUTINE", .95,
            mapOf("phrase" to "browse mode", "steps" to steps.joinToString(",")), learnable = true))!!
        memory().savePreference("browser", chrome)
        assertTrue(registry().saveApproved(proposal))
        memory().savePreference("browser", firefox)
        var launched: LearnableDeviceAction? = null
        val result = RoutineExecution.runWithPreferred(registry().find("browse mode")!!.actions, memory(), { true }) { step, target ->
            if (step == "volume_down") RoutineExecution.Status.VERIFIED
            else { launched = target; RoutineExecution.Status.AWAITING_OUTCOME }
        }
        assertEquals(firefox, launched)
        assertTrue(result.awaitingOutcome)
        assertNull(memory().lastConfirmed("open_app"))
        assertFalse(routines.contains("com.android.chrome"))
        assertFalse(routines.contains("org.mozilla.firefox"))
    }

    @Test fun missingClearedOrUninstalledPreferenceBlocksAllStepsBeforeVolumeChange() {
        var saved = "{}"
        val memory = AssistantMemory({ saved }, { saved = it; true })
        fun blocked(isLaunchable: Boolean) {
            var calls = 0
            val result = RoutineExecution.runWithPreferred(steps, memory, { isLaunchable }) { _, _ -> calls++; RoutineExecution.Status.VERIFIED }
            assertTrue(result.unavailablePreferred)
            assertEquals(0, calls)
            assertTrue(result.steps.isEmpty())
        }
        blocked(true)
        memory.savePreference("browser", chrome)
        blocked(false)
        memory.clear()
        blocked(true)
    }

    @Test fun preferenceChangesMidRunDoNotSwitchTheSnapshottedTarget() {
        var saved = "{}"
        val memory = AssistantMemory({ saved }, { saved = it; true })
        memory.savePreference("browser", chrome)
        var launched: LearnableDeviceAction? = null
        RoutineExecution.runWithPreferred(steps, memory, { true }) { step, target ->
            if (step == "volume_down") {
                assertNull(target)
                memory.savePreference("browser", firefox)
                RoutineExecution.Status.VERIFIED
            } else { launched = target; RoutineExecution.Status.AWAITING_OUTCOME }
        }
        assertEquals(chrome, launched)
        assertEquals(firefox, RoutineSteps.preferredAction("preferred:browser", memory) { true })
    }

    @Test fun currentTargetIsConfirmedOnlyAfterUserOutcomeAndDoesNotBecomeRoutineAlias() {
        var saved = "{}"
        var pending: String? = null
        val memory = AssistantMemory({ saved }, { saved = it; true })
        fun session() = DeviceLearningSession({ pending }, { pending = it; true })
        memory.savePreference("browser", chrome)
        RoutineExecution.runWithPreferred(steps, memory, { true }) { _, target ->
            if (target == null) RoutineExecution.Status.VERIFIED
            else {
                assertEquals(DeviceLearningSession.RequestResult.AWAITING_OUTCOME, session().requestAndTrack("browse mode", target) { true })
                RoutineExecution.Status.AWAITING_OUTCOME
            }
        }
        assertNull(memory.mostRecentConfirmed())
        // Updating a preference while waiting does not change the actual launch being verified.
        memory.savePreference("browser", firefox)
        val outcome = session().consume("it works")!!
        assertTrue(outcome.accepted)
        assertFalse(outcome.pending.learnable)
        assertFalse(outcome.pending.replayed)
        assertTrue(memory.recordConfirmed(outcome.pending.action))
        assertEquals(chrome, memory.mostRecentConfirmed())
        assertNull(session().consume("it works"))
    }

    @Test fun rejectsLiteralAppsInjectedPackagesMultipleTargetsAndNonFinalAppSteps() {
        for (invalid in listOf("preferred:Chrome", "preferred:email", "preferred:browser:com.bad.app", "preferred:browser,volume_down",
            "preferred:browser,timer:300", "timer:300,preferred:browser", "preferred:browser,preferred:maps", "app_update,preferred:music")) {
            val lesson = FallbackIntentLesson("CREATE_ROUTINE", .95, mapOf("phrase" to "browse mode", "steps" to invalid), learnable = true)
            assertNull(RoutineProposal.fromLesson(lesson))
        }
        for (slot in AssistantMemory.slots) assertTrue(RoutineSteps.isValid(listOf("preferred:$slot")))
        assertTrue(RoutineSteps.isValid(listOf("volume_up", "preferred:maps")))
        assertTrue(RoutineSteps.isValid(listOf("volume_down", "timer:300")))
        assertEquals("open the current saved preferred browser app", RoutineProposal.describe("preferred:browser"))
    }
}
