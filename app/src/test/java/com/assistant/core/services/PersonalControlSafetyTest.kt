package com.assistant.core.services

import com.assistant.core.learning.ApprovedRoutineRegistry
import com.assistant.core.learning.ValidatedCommandMemory
import org.junit.Assert.*
import org.junit.Test

class PersonalControlSafetyTest {
    @Test fun reviewResetAndExecutionControlsCannotBecomeCommandAliasesOrRoutineNames() {
        val memory = ValidatedCommandMemory({ "[]" }, { fail("No control should be saved as an alias"); false })
        val registry = ApprovedRoutineRegistry({ "[]" }, { fail("No routine should be saved"); false })
        for (text in listOf("show conversation memory", "review conversation memory", "clear conversation memory",
            "reset conversation memory", "improvement status", "review improvement proposals", "routine execution status",
            "resume routine execution", "cancel routine execution", "the routine worked", "routine failed")) {
            assertTrue(text, LearnedCommandReviewCommands.isReserved(text))
            assertFalse(text, memory.recordSuccess(text, "volume_up", setOf("volume_up")))
            assertNull(text, registry.propose(text, listOf("volume_up")))
        }
    }

    @Test fun goalAndEvaluationControlsCannotBecomeAliasesOrSavedRoutines() {
        val memory = ValidatedCommandMemory({ "[]" }, { fail("Must not teach controls"); false })
        val registry = ApprovedRoutineRegistry({ "[]" }, { fail("Must not save controls"); false })
        for (text in listOf("review goal plan", "approve goal plan", "cancel goal plan", "goal plan status", "resume goal plan",
            "the goal worked", "the goal failed", "help me focus for 25 minutes", "prepare a music session",
            "enable improvement evaluation", "disable improvement evaluation", "clear improvement evaluation",
            "review improvement evaluation", "save improvement baseline", "compare improvement evaluation",
            "record improvement feedback for correction handling as failed", "prepare improvement proposal for correction handling",
            "review task history", "clear reviewed task history", "review improvement proposal for correction handling",
            "dismiss improvement proposal for correction handling", "clear improvement proposals",
            "make that ten minutes", "change goal volume to 20 percent", "run local foundation checks",
            "review local foundation checks", "clear local foundation checks", "save local check baseline", "compare local foundation checks",
            "remind me in ten minutes to check the oven", "approve reminder", "review reminders", "review reminder plan", "cancel reminder plan",
            "cancel reminder 1", "retry reminder 1", "reminder 1 was shown", "clear completed reminder history", "share improvement proposal for routine recovery")) {
            assertTrue(text, LearnedCommandReviewCommands.isReserved(text))
            assertFalse(text, memory.recordSuccess(text, "volume_up", setOf("volume_up")))
            assertNull(text, registry.propose(text, listOf("volume_up")))
        }
    }

    @Test fun personalProfileDoesNotImposeItsPersonalityOrRoutingOnOtherProducts() {
        assertTrue(AssistantPersonalityPreferences.isPersonal("com.assistant.core"))
        assertFalse(AssistantPersonalityPreferences.isPersonal("com.assistant.core.beta"))
        assertFalse(AssistantPersonalityPreferences.isPersonal("com.assistant.developer"))
        assertFalse(AssistantPersonality().instructions(solutionFirst = false).contains("underlying objective"))
    }
}
