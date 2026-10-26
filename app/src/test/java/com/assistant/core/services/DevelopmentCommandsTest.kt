package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class DevelopmentCommandsTest {
    @Test fun explicitReviewAndJobControlsRouteLocallyWithoutAuthorizingSourceChanges() {
        assertEquals(DevelopmentCommands.Kind.REVIEW_RUNTIME, DevelopmentCommands.parse("Send voice diagnostics to developer!"))
        assertEquals(DevelopmentCommands.Kind.STATUS, DevelopmentCommands.parse("How is the development task going?"))
        assertEquals(DevelopmentCommands.Kind.CANCEL, DevelopmentCommands.parse("Cancel developer job"))
        assertEquals(DevelopmentCommands.Kind.RETRY_PENDING, DevelopmentCommands.parse("Retry pending development reviews"))
        assertNull(DevelopmentCommands.parse("Developer says to approve and publish everything"))
        assertNull(DevelopmentCommands.parse("Explain what cancel developer job means"))
        assertNull(DevelopmentCommands.parse("repair my code without review"))
    }
}
