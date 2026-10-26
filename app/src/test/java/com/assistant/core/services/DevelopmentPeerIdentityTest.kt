package com.assistant.core.services

import com.jarvis.integration.DevelopmentPeerIdentity as Identity
import org.junit.Assert.*
import org.junit.Test

class DevelopmentPeerIdentityTest {
    @Test fun aPayloadNameOrMatchingKeyAloneCannotAuthenticateAnotherUid() {
        val allowed = setOf("com.jarvis.developer")
        assertNull(Identity.select(setOf("other.app"), allowed, true))
        assertNull(Identity.select(allowed, allowed, false))
        assertNull(Identity.select(allowed + "other.app", allowed, true))
        assertEquals("com.jarvis.developer", Identity.select(allowed, allowed, true))
    }
}
