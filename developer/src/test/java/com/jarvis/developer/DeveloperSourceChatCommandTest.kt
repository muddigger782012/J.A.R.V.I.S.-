package com.jarvis.developer

import org.junit.Assert.*
import org.junit.Test

class DeveloperSourceChatCommandTest {
    @Test fun recognizesExplicitReadOnlySourceInspection() {
        assertTrue(DeveloperSourceChatCommand.isRequest(
            "Inspect Developer repository for chat response continuity"))
        assertTrue(DeveloperSourceChatCommand.isRequest(
            "Analyze the codebase for missing Back navigation handlers"))
        assertTrue(DeveloperSourceChatCommand.isRequest(
            "Scan Jarvis Developer source tree about approval button visibility"))
        assertFalse(DeveloperSourceChatCommand.isRequest("Inspect repository"))
        assertFalse(DeveloperSourceChatCommand.isRequest("Fix Developer chat response continuity"))
        assertFalse(DeveloperSourceChatCommand.isRequest(
            "Inspect Personal Jarvis repository for mic defects"))
        assertFalse(DeveloperSourceChatCommand.isRequest(
            "Inspect Developer repository for chat bugs and publish it"))
        assertFalse(DeveloperSourceChatCommand.isRequest(
            "Analyze the codebase for changes then approve them"))
    }
}
