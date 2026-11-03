package com.assistant.core.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionDevelopmentCommandsTest {
    @Test fun conversationalDeveloperStatusRoutesToExistingBridge() {
        assertEquals(DevelopmentCommands.Kind.STATUS, DevelopmentCommands.parse("Developer status"))
        assertEquals(DevelopmentCommands.Kind.STATUS, DevelopmentCommands.parse("Track development"))
        assertEquals(DevelopmentCommands.Kind.LIST, DevelopmentCommands.parse("Show developer jobs"))
        assertEquals(DevelopmentCommands.Kind.LIST, DevelopmentCommands.parse("Developer jobs"))
    }

    @Test fun approvalAndInstallationRemainExplicit() {
        assertNull(DevelopmentCommands.parse("yes"))
        assertNull(DevelopmentCommands.parse("approve"))
        assertEquals(DevelopmentCommands.Kind.APPROVE_INSTALL_APK,
            DevelopmentCommands.parse("Approve this APK installation"))
        assertEquals(DevelopmentCommands.Kind.INSTALL_APK,
            DevelopmentCommands.parse("Install the verified APK developer built"))
    }
}
