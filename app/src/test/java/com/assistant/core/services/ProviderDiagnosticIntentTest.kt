package com.assistant.core.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderDiagnosticIntentTest {
    @Test fun routesScreenshotPhrases() {
        assertEquals(ProviderDiagnosticIntent.Kind.GEMINI,
            ProviderDiagnosticIntent.classify("Test Gemini's availability"))
        assertEquals(ProviderDiagnosticIntent.Kind.SEARCH,
            ProviderDiagnosticIntent.classify("Test search connections"))
        assertEquals(ProviderDiagnosticIntent.Kind.INTERNET,
            ProviderDiagnosticIntent.classify("Test internet connections"))
        assertEquals(ProviderDiagnosticIntent.Kind.AI,
            ProviderDiagnosticIntent.classify("Test AI is functional"))
    }

    @Test fun normalizesSpacingAndCase() {
        assertEquals(ProviderDiagnosticIntent.Kind.GEMINI,
            ProviderDiagnosticIntent.classify("  TEST   GEMINI CONNECTION? "))
    }

    @Test fun doesNotHijackOrdinaryConversationOrApproval() {
        assertNull(ProviderDiagnosticIntent.classify("Yes"))
        assertNull(ProviderDiagnosticIntent.classify("What's the result"))
        assertNull(ProviderDiagnosticIntent.classify("Can you explain Gemini models?"))
    }
}
