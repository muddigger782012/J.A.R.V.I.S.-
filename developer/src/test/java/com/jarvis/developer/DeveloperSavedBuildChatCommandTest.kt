package com.jarvis.developer

import org.junit.Assert.*
import org.junit.Test

class DeveloperSavedBuildChatCommandTest {
    @Test fun onlyExplicitDeveloperHistoryCommandsTriggerLocalOperations() {
        assertEquals(DeveloperSavedBuildChatCommand.Action.SAVE,
            DeveloperSavedBuildChatCommand.match("Save current Developer task"))
        assertEquals(DeveloperSavedBuildChatCommand.Action.READ,
            DeveloperSavedBuildChatCommand.match("Check saved Developer build!"))
        assertNull(DeveloperSavedBuildChatCommand.match("How do I save my files?"))
        assertNull(DeveloperSavedBuildChatCommand.match("Publish Developer build"))
        assertNull(DeveloperSavedBuildChatCommand.match("Check saved Personal Jarvis build"))
        assertNull(DeveloperSavedBuildChatCommand.match("Save Developer task and publish"))
    }
}
