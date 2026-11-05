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
}
