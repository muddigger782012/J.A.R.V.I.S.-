package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class ScreenCaptureReadTest {
    @Test fun onlyExplicitReadAndListRequestsWithEnoughRemainingTimePermitTimeoutRecovery() {
        for (kind in ScreenControls.Kind.values()) {
            assertEquals(kind in setOf(ScreenControls.Kind.READ, ScreenControls.Kind.LIST),
                ScreenCaptureRead.mayRetryTimeout(kind, 3000))
        }
        assertFalse(ScreenCaptureRead.mayRetryTimeout(null, 3000))
        for (remaining in listOf(-1L, 0L, 499L)) assertFalse(ScreenCaptureRead.mayRetryTimeout(ScreenControls.Kind.READ, remaining))
        assertTrue(ScreenCaptureRead.mayRetryTimeout(ScreenControls.Kind.READ, 500))
    }
    @Test fun anArmedTimedOutObservationReturnsOnlyItsFreshCompleteReplacement() {
        var attempts = 0; val reasons = mutableListOf<ScreenReadFailure.Reason>()
        val complete = Any()
        val result = ScreenCaptureRead.read({ true }, { true }, { reasons.add(it) }) {
            if (++attempts == 1) throw ScreenReadFailure(ScreenReadFailure.Reason.BUSY)
            complete
        }
        assertSame(complete, result); assertEquals(2, attempts)
        assertEquals(listOf(ScreenReadFailure.Reason.BUSY), reasons)
    }
    @Test fun timeoutRechecksRequireExplicitOptInAndDoNotRunByDefault() {
        var attempts = 0
        val failure = assertThrows(ScreenReadFailure::class.java) {
            ScreenCaptureRead.read({ true }) { attempts++; throw ScreenReadFailure(ScreenReadFailure.Reason.BUSY) }
        }
        assertEquals(ScreenReadFailure.Reason.BUSY, failure.reason); assertEquals(1, attempts)
    }
    @Test fun budgetConsumedByTheFirstReadIsNotResetForASecondAttempt() {
        var remaining = 3000L; var attempts = 0
        assertThrows(ScreenReadFailure::class.java) {
            ScreenCaptureRead.read({ remaining > 0 }, { ScreenCaptureRead.mayRetryTimeout(ScreenControls.Kind.READ, remaining) }) {
                attempts++; remaining = 499; throw ScreenReadFailure(ScreenReadFailure.Reason.BUSY)
            }
        }
        assertEquals(1, attempts); assertEquals(499L, remaining)
    }
    @Test fun cancellationBeforeTheTimeoutRecheckPreventsAnotherCapture() {
        var allowed = true; var attempts = 0; var receipts = 0
        assertThrows(IllegalStateException::class.java) {
            ScreenCaptureRead.read({ allowed }, { true }, { receipts++ }) {
                attempts++; allowed = false; throw ScreenReadFailure(ScreenReadFailure.Reason.BUSY)
            }
        }
        assertEquals(1, attempts); assertEquals(0, receipts)
    }
    @Test fun aSecondTimeoutOrTreeChangeCannotTriggerAThirdCapture() {
        for (second in listOf(ScreenReadFailure(ScreenReadFailure.Reason.BUSY), ScreenCaptureRead.Changed())) {
            var attempts = 0; var policies = 0; var receipts = 0
            val actual = assertThrows(ScreenReadFailure::class.java) {
                ScreenCaptureRead.read({ true }, { policies++; true }, { receipts++ }) {
                    if (++attempts == 1) throw ScreenReadFailure(ScreenReadFailure.Reason.BUSY)
                    throw second
                }
            }
            assertSame(second, actual); assertEquals(2, attempts)
            assertEquals(1, policies); assertEquals(1, receipts)
        }
    }
    @Test fun disconnectionCancellationAndLimitsNeverBecomeTimeoutRetries() {
        for (reason in ScreenReadFailure.Reason.values().filter { it != ScreenReadFailure.Reason.BUSY }) {
            var attempts = 0; var policies = 0
            val original = ScreenReadFailure(reason)
            val actual = assertThrows(ScreenReadFailure::class.java) {
                ScreenCaptureRead.read({ true }, { policies++; true }) { attempts++; throw original }
            }
            assertSame(original, actual); assertEquals(1, attempts); assertEquals(0, policies)
        }
    }
    @Test fun exceptionTextCannotOptAnUnknownFailureIntoTimeoutRecovery() {
        var attempts = 0; var policies = 0
        val original = IllegalArgumentException("BUSY: untrusted private message")
        val actual = assertThrows(IllegalArgumentException::class.java) {
            ScreenCaptureRead.read({ true }, { policies++; true }) { attempts++; throw original }
        }
        assertSame(original, actual); assertEquals(1, attempts); assertEquals(0, policies)
    }
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
