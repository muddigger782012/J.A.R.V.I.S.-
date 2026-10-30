package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class ScreenCaptureReadTest {
    @Test fun platformFreshNodesDoNotTriggerExtraRemoteRefreshes() {
        val freshness = ScreenCaptureRead.Freshness(platformFresh = true)
        repeat(4) { freshness.validate { error("No second remote read is needed for fresh Android nodes") } }
        assertEquals(0, freshness.refreshes)
    }
    @Test fun fallbackNodesMustRefreshAndAnInvalidNodeRejectsTheWholeCapture() {
        val freshness = ScreenCaptureRead.Freshness(platformFresh = false)
        var calls = 0
        freshness.validate { calls++; true }
        freshness.validate { calls++; true }
        assertThrows(ScreenCaptureRead.Changed::class.java) { freshness.validate { calls++; false } }
        assertEquals(3, freshness.refreshes); assertEquals(3, calls)
    }
    @Test fun aFailedFallbackRootCannotReturnPartialDataOrRetryBeyondTheExistingLimit() {
        var attempts = 0; var returned = false
        assertThrows(ScreenCaptureRead.Changed::class.java) {
            ScreenCaptureRead.read({ true }) {
                attempts++
                ScreenCaptureRead.Freshness(false).validate { false }
                returned = true
            }
        }
        assertEquals(2, attempts); assertFalse(returned)
    }
    @Test fun disconnectionDuringRequiredRefreshIsNotRetried() {
        var attempts = 0
        val failure = assertThrows(ScreenReadFailure::class.java) {
            ScreenCaptureRead.read({ true }) {
                attempts++
                ScreenCaptureRead.Freshness(false).validate { throw ScreenReadFailure(ScreenReadFailure.Reason.DISCONNECTED) }
            }
        }
        assertEquals(ScreenReadFailure.Reason.DISCONNECTED, failure.reason); assertEquals(1, attempts)
    }
    @Test fun aChangedTreeIsDiscardedAndOnlyAFreshCompleteReadCanSucceed() {
        var attempts = 0
        val result = ScreenCaptureRead.read({ true }) {
            if (++attempts == 1) throw ScreenCaptureRead.Changed()
            "complete replacement screen"
        }
        assertEquals("complete replacement screen", result)
        assertEquals(2, attempts)
    }
    @Test fun continuousChangesFailClosedWithoutFurtherRetries() {
        var attempts = 0
        assertThrows(ScreenCaptureRead.Changed::class.java) {
            ScreenCaptureRead.read({ true }) { attempts++; throw ScreenCaptureRead.Changed() }
        }
        assertEquals(2, attempts)
    }
    @Test fun cancellationBetweenCapturesPreventsTheReplacementRead() {
        var allowed = true; var attempts = 0
        assertThrows(IllegalStateException::class.java) {
            ScreenCaptureRead.read({ allowed }) { attempts++; allowed = false; throw ScreenCaptureRead.Changed() }
        }
        assertEquals(1, attempts)
    }
    @Test fun limitsAndOtherFailuresAreNeverRetried() {
        var attempts = 0
        assertThrows(IllegalArgumentException::class.java) {
            ScreenCaptureRead.read({ true }) { attempts++; throw IllegalArgumentException("Capture limit") }
        }
        assertEquals(1, attempts)
        assertThrows(IllegalStateException::class.java) {
            ScreenCaptureRead.read({ false }) { attempts++; "must not read" }
        }
        assertEquals(1, attempts)
    }
}
