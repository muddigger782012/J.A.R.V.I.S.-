package com.assistant.core.services

import org.junit.Assert.assertEquals
import org.junit.Test

class WakeRecoveryGuardTest {
    @Test fun waitsForNativeWorkerBeforeReopeningMicrophone() {
        assertEquals(WakeRecoveryGuard.Decision.WAIT, WakeRecoveryGuard.decision(true, 0))
        assertEquals(WakeRecoveryGuard.Decision.WAIT, WakeRecoveryGuard.decision(true, 29))
        assertEquals(WakeRecoveryGuard.Decision.RESTART, WakeRecoveryGuard.decision(false, 1))
    }

    @Test fun stalledDecoderDoesNotCauseUnsafeSecondWorker() {
        assertEquals(WakeRecoveryGuard.Decision.FAIL, WakeRecoveryGuard.decision(true, 30))
        assertEquals(WakeRecoveryGuard.Decision.RESTART, WakeRecoveryGuard.decision(false, 30))
    }
}
