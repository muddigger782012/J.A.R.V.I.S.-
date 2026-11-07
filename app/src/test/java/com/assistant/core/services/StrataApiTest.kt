package com.assistant.core.services

import com.jarvis.integration.StrataApi
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StrataApiTest {
    @Test fun permitsExplicitLocalAddressesButNotPublicCleartext() {
        assertEquals("http://192.168.1.20:8080/v1/chat/completions",
            StrataApi.endpoint("http://192.168.1.20:8080/v1").toString())
        assertTrue(StrataApi.supported("http://100.101.2.3:8080/v1"))
        assertTrue(StrataApi.supported("https://strata.example.com/v1"))
        assertFalse(StrataApi.supported("http://example.com/v1"))
        assertFalse(StrataApi.supported("http://192.168.1.5.evil.com/v1"))
        assertFalse(StrataApi.supported("http://127.0.0.1@evil.com/v1"))
        assertFalse(StrataApi.supported("https://example.com/v1?key=secret"))
    }

    @Test fun readsOpenAiCompatibleTextWithoutExecutingTools() {
        val success = JSONObject("""{"choices":[{"message":{"role":"assistant","content":"Connected"}}]}""")
        assertEquals("Connected", StrataApi.parseText(success))
        val tools = JSONObject("""{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"call_1"}]}}]}""")
        assertThrows(IllegalArgumentException::class.java) { StrataApi.parseText(tools) }
    }

    @Test fun developerCredentialKeepsKeyOutOfEndpointUrl() {
        val config = StrataApi.fromConnectionField("http://10.0.0.4:8080/v1#private-key", "")
        assertEquals("private-key", config.token)
        assertEquals("strata", config.model)
        assertFalse(StrataApi.endpoint(config.server).toString().contains("private-key"))
    }
}
