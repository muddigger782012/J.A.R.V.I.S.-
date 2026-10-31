package com.jarvis.developer

import org.junit.Assert.*
import org.junit.Test

class DevelopmentDeliveryGateTest {
    @Test fun baselineNeverClaimsUnverifiedDelivery() {
        val items = DevelopmentDeliveryGate.baseline
        assertEquals(24, items.size)
        assertEquals(0, DevelopmentDeliveryGate.verifiedCount(items))
        assertFalse(DevelopmentDeliveryGate.readyToPublish(items))
    }

    @Test fun evidenceIsRequiredForVerifiedWork() {
        assertThrows(IllegalArgumentException::class.java) {
            DevelopmentDeliveryGate.Capability(1, "AI coding engine", DevelopmentDeliveryGate.State.VERIFIED)
        }
        val original = DevelopmentDeliveryGate.baseline.first()
        val verified = original.copy(state = DevelopmentDeliveryGate.State.VERIFIED,
            evidenceUrl = "https://github.com/example/repo/actions/runs/123")
        val updated = DevelopmentDeliveryGate.update(DevelopmentDeliveryGate.baseline, verified)
        assertEquals(1, DevelopmentDeliveryGate.verifiedCount(updated))
        assertFalse(DevelopmentDeliveryGate.readyToPublish(updated))
    }

    @Test fun cannotRewriteCapabilityIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            DevelopmentDeliveryGate.update(DevelopmentDeliveryGate.baseline,
                DevelopmentDeliveryGate.Capability(1, "Different requirement"))
        }
    }
}
