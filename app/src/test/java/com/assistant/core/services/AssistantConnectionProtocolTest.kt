package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class AssistantConnectionProtocolTest {
    @Test fun providerRequestsRequireAnExplicitPrefix() {
        assertEquals(AssistantConnectionProtocol.Request("home_assistant", "turn on the lights"), AssistantConnectionProtocol.parse("Ask Home Assistant to turn on the lights"))
        assertNull(AssistantConnectionProtocol.parse("Google Assistant what is on my calendar?"))
        assertEquals("mycroft", AssistantConnectionProtocol.parse("OpenVoiceOS what time is it?")?.provider)
        assertNull(AssistantConnectionProtocol.parse("What is Google Assistant?"))
        assertNull(AssistantConnectionProtocol.parse("Call mycroft"))
        assertNull(AssistantConnectionProtocol.parse("Home Assistant"))
    }
    @Test fun credentialsCannotBeEmbeddedInAnEndpoint() {
        assertTrue(AssistantConnectionProtocol.validEndpoint("https://home.example:8123"))
        assertFalse(AssistantConnectionProtocol.validEndpoint("http://home.example"))
        assertFalse(AssistantConnectionProtocol.validEndpoint("https://token@home.example"))
        assertFalse(AssistantConnectionProtocol.validEndpoint("https://home.example?token=secret"))
        assertFalse(AssistantConnectionProtocol.validEndpoint("https://"))
    }
    @Test fun homeAssistantResponseIsUsedWithoutInventingSuccess() {
        val answer = JSONObject("""{"response":{"response_type":"query_answer","speech":{"plain":{"speech":"Kitchen is 70 degrees"}}}}""")
        assertEquals("Kitchen is 70 degrees", AssistantConnectionProtocol.homeReply(answer))
        assertTrue(AssistantConnectionProtocol.homeReply(JSONObject("""{"response":{"response_type":"error"}}""")).contains("couldn't"))
        assertFalse(AssistantConnectionProtocol.homeReply(JSONObject()).contains("completed"))
    }
}
