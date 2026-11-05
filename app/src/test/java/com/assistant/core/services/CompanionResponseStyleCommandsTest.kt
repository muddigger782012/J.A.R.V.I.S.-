package com.assistant.core.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionResponseStyleCommandsTest {
    @Test fun screenshotFollowupSavesCliffNotesForFutureFeatures() {
        assertEquals(CompanionResponseStyleCommands.Request.FEATURE_BRIEF,
            CompanionResponseStyleCommands.parse(
                "In the future when I ask you about your new features give me the cliff notes version instead first"))
        assertEquals(CompanionResponseStyleCommands.Request.FEATURE_BRIEF,
            CompanionResponseStyleCommands.parse("Keep your update summaries brief by default"))
    }

    @Test fun explicitSavedGeneralStyleChoices() {
        assertEquals(CompanionResponseStyleCommands.Request.GENERAL_BRIEF,
            CompanionResponseStyleCommands.parse("Keep your replies concise by default"))
        assertEquals(CompanionResponseStyleCommands.Request.GENERAL_DETAILED,
            CompanionResponseStyleCommands.parse("From now on give me detailed answers"))
        assertEquals(CompanionResponseStyleCommands.Request.GENERAL_BALANCED,
            CompanionResponseStyleCommands.parse("Set response style to balanced"))
    }

    @Test fun queriesAndNegationsNeverMutateSettings() {
        for (phrase in listOf("Give me the full release notes", "What are your new features?",
            "Don't make your answers brief", "Explain how to save my response preferences",
            "Ask Developer to modify the source code", "Open the current APK review")) {
            assertNull(phrase, CompanionResponseStyleCommands.parse(phrase))
        }
    }
}
