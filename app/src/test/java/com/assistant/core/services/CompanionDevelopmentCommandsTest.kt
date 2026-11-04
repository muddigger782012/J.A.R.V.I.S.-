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

    @Test fun explicitDevelopmentTaskIsBoundedAndRequiresPrefix() {
        val request = "Developer task: inspect the microphone pipeline and prepare a review"
        assertEquals(DevelopmentCommands.Kind.SUBMIT, DevelopmentCommands.parse(request))
        assertEquals("inspect the microphone pipeline and prepare a review",
            DevelopmentCommands.submission(request))
        assertNull(DevelopmentCommands.parse("inspect the microphone pipeline"))
        assertNull(DevelopmentCommands.submission("Developer task: short"))
        assertNull(DevelopmentCommands.submission("Developer task: " + "a".repeat(1501)))
    }

    @Test fun naturalLanguageDeveloperRequestsKeepConversationAndApprovalsSeparate() {
        val task = "inspect the conversation memory and propose a continuity fix"
        assertEquals(task, DevelopmentCommands.submission("Ask Jarvis Developer to $task"))
        assertEquals(task, DevelopmentCommands.submission("Please tell developer to $task"))
        assertEquals(task, DevelopmentCommands.submission("Have developer $task"))
        assertEquals(DevelopmentCommands.Kind.SUBMIT,
            DevelopmentCommands.parse("Ask developer to $task"))
        assertNull(DevelopmentCommands.parse("Can you inspect the conversation memory?"))
        assertNull(DevelopmentCommands.parse("Ask developer to approve"))
        assertNull(DevelopmentCommands.parse("Ask developer to " + "x".repeat(1501)))
    }

    @Test fun conversationalFollowUpsUseExistingJobInsteadOfSubmittingDuplicateWork() {
        assertEquals(DevelopmentCommands.Kind.STATUS,
            DevelopmentCommands.parse("Where did we leave off with development?"))
        assertEquals(DevelopmentCommands.Kind.STATUS,
            DevelopmentCommands.parse("What happened with my developer task?"))
        assertEquals(DevelopmentCommands.Kind.STATUS,
            DevelopmentCommands.parse("What is the Developer working on?"))
        assertEquals(DevelopmentCommands.Kind.RESUME,
            DevelopmentCommands.parse("Continue the paused developer job"))
        assertNull(DevelopmentCommands.submission("Where did we leave off with development?"))
        assertNull(DevelopmentCommands.parse("Continue our conversation"))
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
