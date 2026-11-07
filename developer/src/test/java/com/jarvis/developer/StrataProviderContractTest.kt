package com.jarvis.developer

import com.jarvis.integration.StrataApi
import org.junit.Assert.*
import org.junit.Test

class StrataProviderContractTest {
    @Test fun providerAcceptsExplicitHttpsOrLanEndpoint() {
        assertEquals("https://strata.example.org/v1/chat/completions",
            StrataApi.endpoint("https://strata.example.org/v1").toString())
        assertEquals("strata", StrataApi.fromConnectionField("http://192.168.0.42:8080/v1").model)
        assertFalse(StrataApi.supported("http://public.example.org/v1"))
    }
}
