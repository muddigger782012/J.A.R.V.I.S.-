package com.jarvis.developer

import org.junit.Assert.*
import org.junit.Test

class DeveloperRepairChatCommandTest {
    @Test fun recognizesOnlyExplicitOwnerActions() {
        assertTrue(DeveloperRepairChatCommand.isRequest("Prepare Developer repair"))
        assertTrue(DeveloperRepairChatCommand.isRequest("Repair the failed Developer build!"))
        assertFalse(DeveloperRepairChatCommand.isRequest("What went wrong with the build?"))
        assertFalse(DeveloperRepairChatCommand.isRequest("Please explain the failed Developer build"))
        assertFalse(DeveloperRepairChatCommand.isRequest("Prepare Personal Jarvis repair"))
        assertFalse(DeveloperRepairChatCommand.isRequest("Prepare Developer repair and publish without review"))
    }
}
