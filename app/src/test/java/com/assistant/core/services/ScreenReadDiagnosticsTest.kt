package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class ScreenReadDiagnosticsTest {
    @Test fun recaptureReportsTheActualFirstFailureAndBoundsItsWholeReadDuration() {
        val record = ScreenReadDiagnostics(recapture = ScreenReadDiagnostics.Recapture(ScreenReadFailure.Reason.BUSY, Long.MAX_VALUE))
        val report = record.report()
        assertTrue(report.contains("attempt: completed"))
        assertTrue(report.contains("Captures: 2. First capture: time limit reached. Total read time: 60000 ms"))
        assertTrue(record.copy(recapture = ScreenReadDiagnostics.Recapture(ScreenReadFailure.Reason.CHANGED, -1)).report()
            .contains("Captures: 2. First capture: screen changed. Total read time: 0 ms"))
        assertFalse(ScreenReadDiagnostics().report().contains("Captures: 2"))
    }
    @Test fun recaptureMetadataCannotConvertFailureOrProtectionIntoSuccessfulContentAccess() {
        val receipt = ScreenReadDiagnostics.Recapture(ScreenReadFailure.Reason.BUSY, 2400)
        for (reason in ScreenReadFailure.Reason.values()) {
            val report = ScreenReadDiagnostics(reason = reason, recapture = receipt).report()
            assertFalse(report.contains("attempt: completed")); assertTrue(report.contains("Captures: 2"))
        }
        val report = ScreenReadDiagnostics(protectedScreen = true, nodes = 123, refreshes = 123, recapture = receipt).report()
        assertTrue(report.contains("protected screen; no content exposed"))
        assertTrue(report.contains("Examined nodes: 0")); assertTrue(report.contains("Explicit refresh calls: 0"))
        assertFalse(report.contains("attempt: completed"))
    }
    @Test fun nonRecoverableReasonsCannotFabricateARecaptureReceipt() {
        for (reason in ScreenReadFailure.Reason.values().filter { it !in setOf(ScreenReadFailure.Reason.BUSY, ScreenReadFailure.Reason.CHANGED) }) {
            assertThrows(IllegalArgumentException::class.java) { ScreenReadDiagnostics.Recapture(reason, 0) }
        }
    }
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
        val record = ScreenReadDiagnostics(protectedScreen = true, nodes = 100, refreshes = 100)
        assertTrue(record.report().contains("protected screen; no content exposed"))
        assertTrue(record.report().contains("Examined nodes: 0"))
        assertTrue(record.report().contains("Explicit refresh calls: 0"))
        assertFalse(record.report().contains("completed"))
        assertTrue(record.copy(reason = ScreenReadFailure.Reason.BUSY).report().contains("Examined nodes: 0"))
    }

    @Test fun phaseQueueAndRefreshMetricsStayBoundedAndExplainOnlyThePreviousAttempt() {
        val diagnostic = ScreenReadDiagnostics(queueMillis = Long.MAX_VALUE, refreshes = Int.MAX_VALUE,
            phases = ScreenReadDiagnostics.Phases(-1, Long.MAX_VALUE, 17, 19, 23),
            refreshPolicy = ScreenReadDiagnostics.RefreshPolicy.EXPLICIT_REQUIRED)
        val report = diagnostic.report()
        assertTrue(report.contains("Queue wait: 60000 ms"))
        assertTrue(report.contains("access 0; root 60000; window 17; controls 19; verification 23"))
        assertTrue(report.contains("Explicit refresh calls: 300"))
        assertTrue(report.contains("Read mode: explicit refresh required"))
        assertTrue(report.contains("not the current screen"))
        assertTrue(diagnostic.copy(queueMillis = -1, refreshes = -1).report().contains("Queue wait: 0 ms"))
        assertTrue(diagnostic.copy(refreshes = -1).report().contains("Explicit refresh calls: 0"))
    }

    @Test fun refreshPolicyCannotTurnATimeoutOrUnknownCaptureIntoASuccessClaim() {
        for (policy in ScreenReadDiagnostics.RefreshPolicy.values()) {
            val report = ScreenReadDiagnostics(reason = ScreenReadFailure.Reason.BUSY, refreshPolicy = policy,
                phases = ScreenReadDiagnostics.Phases(rootMillis = 1400), queueMillis = 900).report()
            assertTrue(report.contains("time limit reached")); assertFalse(report.contains("attempt: completed"))
            assertTrue(report.contains("Queue wait: 900 ms")); assertTrue(report.contains("root 1400"))
        }
    }

    @Test fun everyFailureRemainsAFailureInTheDiagnostic() {
        for (reason in ScreenReadFailure.Reason.values()) {
            val report = ScreenReadDiagnostics(reason = reason).report()
            assertFalse(report.contains("attempt: completed"))
            assertTrue(report.contains("Request a fresh read"))
        }
    }
}
