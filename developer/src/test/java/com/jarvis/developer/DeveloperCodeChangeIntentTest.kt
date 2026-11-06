package com.jarvis.developer

import org.junit.Assert.*
import org.junit.Test

class DeveloperCodeChangeIntentTest {
    @Test fun explicitDeveloperEngineeringImperativesEnterProposalOnlyMode() {
        assertTrue(DeveloperCodeChangeIntent.isRequest("Fix Developer voice network latency"))
        assertTrue(DeveloperCodeChangeIntent.isRequest("Implement retries in the Developer app"))
        assertTrue(DeveloperCodeChangeIntent.isRequest("Refactor Jarvis Developer code for faster startup"))
        assertTrue(DeveloperCodeChangeIntent.isRequest("Add tests in developer"))
        assertTrue(DeveloperCodeChangeIntent.isRequest("Build a new Android app in Developer"))
    }

    @Test fun questionsAndProductOperationsDoNotTriggerCodingMode() {
        assertFalse(DeveloperCodeChangeIntent.isRequest("How should I fix Developer voice latency?"))
        assertFalse(DeveloperCodeChangeIntent.isRequest("Explain the Developer build process"))
        assertFalse(DeveloperCodeChangeIntent.isRequest("Update Developer"))
        assertFalse(DeveloperCodeChangeIntent.isRequest("Check Developer build"))
        assertFalse(DeveloperCodeChangeIntent.isRequest("Show Developer diff"))
        assertFalse(DeveloperCodeChangeIntent.isRequest("Prepare Developer repair"))
        assertFalse(DeveloperCodeChangeIntent.isRequest("Approve Developer diff 0123456789abcdef"))
        assertFalse(DeveloperCodeChangeIntent.isRequest("Fix the Personal Jarvis microphone"))
        assertFalse(DeveloperCodeChangeIntent.isRequest("Fix voice network latency"))
    }

    @Test fun whitespaceAndVeryLongPromptsAreBounded() {
        assertTrue(DeveloperCodeChangeIntent.isRequest(" Fix   Developer voice network latency "))
        assertFalse(DeveloperCodeChangeIntent.isRequest("Fix Developer app " + "x".repeat(12_001)))
    }
    @Test fun personalImperativesAreTargetSpecificAndNeverAuthorizeDeveloperWrites() {
        assertTrue(PersonalCodeChangeIntent.isRequest("Fix my personal Jarvis voice latency"))
        assertTrue(PersonalCodeChangeIntent.isRequest("Implement recurring reminders in Personal J.A.R.V.I.S."))
        assertTrue(PersonalCodeChangeIntent.isRequest("Add an offline list in my personal app"))
        assertFalse(PersonalCodeChangeIntent.isRequest("What new features would you propose for my personal J.A.R.V.I.S. app?"))
        assertFalse(PersonalCodeChangeIntent.isRequest("Fix Developer voice network latency"))
        assertFalse(PersonalCodeChangeIntent.isRequest("Fix the microphone"))
        assertFalse(PersonalCodeChangeIntent.isRequest("Explain Personal Jarvis source"))
    }

    @Test fun brainstormingCannotBeMistakenForAnActionableSourceChange() {
        assertTrue(DevelopmentExplorationIntent.isRequest("What new features would you propose for my personal i.a.r.v.i.s. app"))
        assertTrue(DevelopmentExplorationIntent.isRequest("Suggest improvements for Personal Jarvis"))
        assertFalse(DevelopmentExplorationIntent.isRequest("Implement recurring reminders in Personal J.A.R.V.I.S."))
        assertFalse(DevelopmentExplorationIntent.isRequest("Repair Developer chat code"))
    }

}
