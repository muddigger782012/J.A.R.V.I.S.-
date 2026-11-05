package com.jarvis.developer

import org.junit.Assert.*
import org.junit.Test

class DeveloperDiffApprovalCommandTest {
    @Test fun requiresAnExactCryptographicProposalPrefix() {
        val id = "0123456789abcdef" + "a".repeat(48)
        assertEquals("0123456789abcdef",
            DeveloperDiffApprovalCommand.parse("Approve Developer diff 0123456789abcdef"))
        assertTrue(DeveloperDiffApprovalCommand.matches("0123456789abcdef", id))
        assertEquals(DeveloperDiffApprovalCommand.REVIEWED,
            DeveloperDiffApprovalCommand.parse("Approve Developer diff"))
        assertTrue(DeveloperDiffApprovalCommand.matches(DeveloperDiffApprovalCommand.REVIEWED, id))
        assertFalse(DeveloperDiffApprovalCommand.matches("0123456789abcde", id))
        assertFalse(DeveloperDiffApprovalCommand.matches("aaaaaaaaaaaaaaaa", id))
        assertFalse(DeveloperDiffApprovalCommand.matches("0123456789abcdef", "b".repeat(64)))
    }

    @Test fun partialOrConcatenatedCommandsCannotGrantApproval() {
        assertTrue(DeveloperDiffApprovalCommand.isAttempt("approve developer diff"))
        assertNull(DeveloperDiffApprovalCommand.parse("approve developer diff and publish"))
        assertNull(DeveloperDiffApprovalCommand.parse("approve developer diff."))
        assertNull(DeveloperDiffApprovalCommand.parse("approve developer diff 0123456789abcdef and publish"))
        assertNull(DeveloperDiffApprovalCommand.parse("please approve developer diff 0123456789abcdef"))
        assertNull(DeveloperDiffApprovalCommand.parse("approve developer diff 0123456789abcdeg"))
        assertNull(DeveloperDiffApprovalCommand.parse("approve personal diff 0123456789abcdef"))
    }
}
