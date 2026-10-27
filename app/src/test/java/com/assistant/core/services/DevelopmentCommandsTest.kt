package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class DevelopmentCommandsTest {
    @Test fun explicitApkRequestsRouteToReviewAcrossNaturalVariations() {
        for (text in listOf("Install the APK Developer just built", "Can you install the APK that Developer built?",
            "Please open the verified APK review in Developer", "Review my APK from Developer",
            "Show me the APK review for my development job", "Open Developer’s APK review"))
            assertEquals(text, DevelopmentCommands.Kind.REVIEW_APK, DevelopmentCommands.parse(text))
    }
    @Test fun questionsNegationsAndUnrelatedInstallRequestsCannotTriggerDevelopmentHandoff() {
        for (text in listOf("Don't install the APK Developer just built", "Explain how to install the APK Developer built",
            "What does open the APK review mean?", "Install WhatsApp", "install that APK", "Confirm installation", "yes"))
            assertNull(text, DevelopmentCommands.parse(text))
    }
    @Test fun explicitReviewAndJobControlsRouteLocallyWithoutAuthorizingSourceChanges() {
        assertEquals(DevelopmentCommands.Kind.REVIEW_RUNTIME, DevelopmentCommands.parse("Send voice diagnostics to developer!"))
        assertEquals(DevelopmentCommands.Kind.STATUS, DevelopmentCommands.parse("How is the development task going?"))
        assertEquals(DevelopmentCommands.Kind.CANCEL, DevelopmentCommands.parse("Cancel developer job"))
        assertEquals(DevelopmentCommands.Kind.RETRY_PENDING, DevelopmentCommands.parse("Retry pending development reviews"))
        assertEquals(DevelopmentCommands.Kind.ANALYZE_PERSONAL, DevelopmentCommands.parse("Ask Developer to inspect your source"))
        assertEquals(DevelopmentCommands.Kind.ANALYZE_DEVELOPER, DevelopmentCommands.parse("Analyze the developer source"))
        assertNull(DevelopmentCommands.parse("Developer says to approve and publish everything"))
        assertNull(DevelopmentCommands.parse("Explain what cancel developer job means"))
        assertNull(DevelopmentCommands.parse("repair my code without review"))
    }
}
