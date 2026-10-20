package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class LocalFoundationChecksTest {
    @Test fun actualCoreChecksRunInIsolationAndReportAllCasesWithoutAndroidTools() {
        val result = LocalFoundationChecks.run()
        assertEquals(LocalFoundationChecks.Case.values().toSet(), result.map { it.case }.toSet())
        assertTrue(result.all { it.outcome == LocalFoundationChecks.Outcome.PASSED })
        assertEquals(result, LocalFoundationChecks.run())
    }

    @Test fun failedAssertionsAndInterruptedChecksRemainVisibleAndDoNotPreventOtherChecks() {
        val results = LocalFoundationChecks.run(mapOf(
            LocalFoundationChecks.Case.APPROVAL to { false },
            LocalFoundationChecks.Case.RECOVERY to { throw IllegalStateException("Synthetic failure") }
        )).associateBy { it.case }
        assertEquals(LocalFoundationChecks.Outcome.FAILED, results.getValue(LocalFoundationChecks.Case.APPROVAL).outcome)
        assertEquals(LocalFoundationChecks.Outcome.ERROR, results.getValue(LocalFoundationChecks.Case.RECOVERY).outcome)
        assertEquals(LocalFoundationChecks.Outcome.PASSED, results.getValue(LocalFoundationChecks.Case.HISTORY).outcome)
    }
}
