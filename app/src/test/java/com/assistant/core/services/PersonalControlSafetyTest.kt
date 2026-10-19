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
            "resume routine execution", "cancel routine execution")) {
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
