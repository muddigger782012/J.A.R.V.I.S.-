package com.assistant.core.adapters

import org.junit.Assert.*
import org.junit.Test

class AccessibilityActionResultTest {
    @Test fun rejectedAndExceptionalActionsNeverReportSuccessOrRepeat() {
        var attempts = 0
        val rejected = AccessibilityActionResult.perform("request", "Back") { attempts++; false }
        assertFalse(rejected.success); assertTrue(rejected.message.contains("not accepted"))
        val interrupted = AccessibilityActionResult.perform("request", "Home") { attempts++; error("private screen information") }
        assertFalse(interrupted.success); assertFalse(interrupted.message.contains("private screen"))
        assertEquals(2, attempts)
    }

    @Test fun acceptedRequestsDescribeAcceptanceWithoutClaimingFinalCompletion() {
        val result = AccessibilityActionResult.perform("request", "Recents") { true }
        assertTrue(result.success); assertEquals("request", result.id)
        assertTrue(result.message.contains("accepted by Android")); assertFalse(result.message.contains("completed"))
    }
}
