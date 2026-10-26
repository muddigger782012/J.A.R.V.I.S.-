package com.jarvis.integration.commercialfixture

import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.integration.DevelopmentBridgeClient
import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.junit.Assert.*
import org.junit.Test

/** Tests the actual installed commercial package identity, not a caller-supplied edition flag. */
class CommercialPeerTest {
    @Test fun commercialUidCannotNegotiateOrSubmitOwnerDevelopmentJobs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.assistant.core.beta", context.packageName)
        val client = DevelopmentBridgeClient(context)
        val capabilities = client.request(Wire.Request("commercial-caps", "capabilities.request"))
        assertEquals("OK", capabilities.getString("code"))
        assertEquals(2, capabilities.getJSONObject("data").getJSONArray("modes").length())
        assertEquals("POLICY_REQUIRED", client.request(Wire.Request("commercial-owner", "capabilities.request",
            mode = "owner_development")).getString("code"))
        val failure = assertThrows(Wire.Rejected::class.java) { client.request(Wire.Request("commercial-admission",
            "diagnostics.review.request", "Unapproved commercial request", mode = "owner_development")) }
        assertEquals("POLICY_REQUIRED", failure.code)
        val accepted = client.request(Wire.Request("commercial-review", "diagnostics.review.request", "Read-only supervised commercial review"))
        assertEquals("OK", accepted.getString("code")); assertFalse(accepted.getJSONObject("data").getBoolean("sourceVerified"))
    }
}
