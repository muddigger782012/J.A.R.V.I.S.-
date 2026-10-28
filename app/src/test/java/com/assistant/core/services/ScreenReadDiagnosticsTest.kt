package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class ScreenReadDiagnosticsTest {
    @Test fun fixedReasonsGiveDifferentRecoveryGuidanceWithoutExceptionContents() {
        val replies = ScreenReadFailure.Reason.values().map { ScreenReadFailure.guidance(ScreenReadFailure(it)) }
        assertEquals(ScreenReadFailure.Reason.values().size, replies.distinct().size)
        assertTrue(replies[ScreenReadFailure.Reason.BUSY.ordinal].contains("Let the screen settle"))
        assertFalse(replies[ScreenReadFailure.Reason.BUSY.ordinal].contains("Check Android Accessibility access"))
        assertTrue(replies[ScreenReadFailure.Reason.DISCONNECTED.ordinal].contains("Accessibility disconnected"))
        assertTrue(replies[ScreenReadFailure.Reason.LIMIT.ordinal].contains("smaller section"))
        val untrusted = "private password, screen label and provider token"
        assertFalse(ScreenReadFailure.guidance(IllegalArgumentException(untrusted)).contains(untrusted))
        val typed = ScreenReadFailure(ScreenReadFailure.Reason.BUSY).apply { initCause(IllegalArgumentException(untrusted)) }
        assertFalse(ScreenReadFailure.guidance(typed).contains(untrusted))
    }

    @Test fun historicalReportsBoundMetadataAndCannotBecomeAClaimAboutTheCurrentScreen() {
        val record = ScreenReadDiagnostics(stage = ScreenReadDiagnostics.Stage.CONTROLS, nodes = Int.MAX_VALUE,
            elapsedMillis = Long.MAX_VALUE, cache = ScreenReadDiagnostics.Cache.DISABLED)
        val report = record.report()
        assertTrue(report.contains("Examined nodes: 300")); assertTrue(report.contains("Time: at least 60000 ms"))
        assertTrue(report.contains("Android cache: disabled")); assertTrue(report.contains("not the current screen"))
        assertTrue(report.contains("no screen text or user values"))
        assertTrue(record.copy(nodes = -1, elapsedMillis = -1).report().contains("Examined nodes: 0. Time: 0 ms"))
    }

    @Test fun protectedReadsExposeNeitherContentNorNodeCounts() {
        val record = ScreenReadDiagnostics(protectedScreen = true, nodes = 100)
        assertTrue(record.report().contains("protected screen; no content exposed"))
        assertTrue(record.report().contains("Examined nodes: 0"))
        assertFalse(record.report().contains("completed"))
        assertTrue(record.copy(reason = ScreenReadFailure.Reason.BUSY).report().contains("Examined nodes: 0"))
    }

    @Test fun everyFailureRemainsAFailureInTheDiagnostic() {
        for (reason in ScreenReadFailure.Reason.values()) {
            val report = ScreenReadDiagnostics(reason = reason).report()
            assertFalse(report.contains("attempt: completed"))
            assertTrue(report.contains("Request a fresh read"))
        }
    }
}
