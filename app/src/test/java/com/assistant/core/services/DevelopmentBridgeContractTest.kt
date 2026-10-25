package com.assistant.core.services

import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DevelopmentBridgeContractTest {
    @Test fun reviewDataRoundTripsWithoutGrantingAnOperationOrAcceptingClaimedIdentity() {
        val value = Wire.Request("review-one", "diagnostics.review.request", "Unverified source finding", "supervised")
        assertEquals(value, Wire.decode(Wire.encode(value)))
        val forged = JSONObject(Wire.encode(value)).put("caller", "com.assistant.core").toString()
        assertThrows(Wire.Rejected::class.java) { Wire.decode(forged) }
        assertThrows(Wire.Rejected::class.java) { Wire.encode(value.copy(kind = "source.write")) }
    }
    @Test fun ownerModeIsRejectedForEitherCommercialParticipantAtThePolicyBoundary() {
        assertTrue(Wire.permitsMode("owner_development", true, true))
        for (receiver in listOf(false, true)) for (sender in listOf(false, true)) {
            if (!receiver || !sender) assertFalse(Wire.permitsMode("owner_development", receiver, sender))
            assertTrue(Wire.permitsMode("authorized_autonomous", receiver, sender))
        }
        assertFalse(Wire.permitsMode("unknown", true, true))
    }
    @Test fun unicodeByteLimitsAndNestedPayloadsAreBoundedBeforeParsing() {
        assertThrows(Wire.Rejected::class.java) { Wire.encode(Wire.Request("large", "diagnostics.review.request", "界".repeat(4000))) }
        assertThrows(Wire.Rejected::class.java) { Wire.decode("[".repeat(100) + "]".repeat(100)) }
        assertThrows(Wire.Rejected::class.java) { Wire.encode(Wire.Request("null", "diagnostics.review.request", "a\u0000b")) }
    }
    @Test fun statusAndCancellationNeedAnExactJobAndResponsesNeedMatchingCorrelation() {
        assertThrows(Wire.Rejected::class.java) { Wire.encode(Wire.Request("status", "task.status.request")) }
        assertThrows(Wire.Rejected::class.java) { Wire.encode(Wire.Request("status", "task.status.request", jobId = "../outside")) }
        assertThrows(Wire.Rejected::class.java) { Wire.readResponse(Wire.response("one", "OK"), "two") }
        val job = Wire.Request("status", "task.status.request", jobId = "review-one")
        assertEquals(job, Wire.decode(Wire.encode(job)))
    }
    @Test fun modeAndScopeChangesCannotReuseAnImmutableRequestFingerprint() {
        val request = Wire.Request("one", "diagnostics.review.request", "First source review")
        assertNotEquals(Wire.fingerprint(request), Wire.fingerprint(request.copy(body = "Another source review")))
        assertNotEquals(Wire.fingerprint(request), Wire.fingerprint(request.copy(mode = "owner_development")))
        assertThrows(Wire.Rejected::class.java) { Wire.decode(JSONObject(Wire.encode(request)).put("v", "1").toString()) }
        assertThrows(Wire.Rejected::class.java) { Wire.encode(request.copy(version = 99)) }
    }
}
