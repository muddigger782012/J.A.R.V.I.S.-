package com.jarvis.developer

import org.junit.Assert.*
import org.junit.Test

class DeveloperDiffChatCommandTest {
    @Test fun recognizesOnlyExplicitReadOnlyReviewCommands() {
        assertTrue(DeveloperDiffChatCommand.isRequest("Show Developer diff"))
        assertTrue(DeveloperDiffChatCommand.isRequest("Review the Developer diff!"))
        assertTrue(DeveloperDiffChatCommand.isRequest("Show my Developer code changes"))
        assertFalse(DeveloperDiffChatCommand.isRequest("Show Personal Jarvis diff"))
        assertFalse(DeveloperDiffChatCommand.isRequest("Show Developer diff and publish"))
        assertFalse(DeveloperDiffChatCommand.isRequest("Approve Developer diff 0123456789abcdef"))
    }
}
