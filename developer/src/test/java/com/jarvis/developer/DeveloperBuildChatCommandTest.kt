package com.jarvis.developer

import org.junit.Assert.*
import org.junit.Test

class DeveloperBuildChatCommandTest {
    @Test fun onlyExplicitDeveloperBuildRequestsAreActions() {
        assertTrue(DeveloperBuildChatCommand.isRequest("Check Developer build"))
        assertTrue(DeveloperBuildChatCommand.isRequest("Check my Developer build!"))
        assertTrue(DeveloperBuildChatCommand.isRequest("Refresh Developer build"))
        assertFalse(DeveloperBuildChatCommand.isRequest("What is a CI workflow?"))
        assertFalse(DeveloperBuildChatCommand.isRequest("Check Personal Jarvis build"))
        assertFalse(DeveloperBuildChatCommand.isRequest("Check Developer build and publish it"))
    }
}
