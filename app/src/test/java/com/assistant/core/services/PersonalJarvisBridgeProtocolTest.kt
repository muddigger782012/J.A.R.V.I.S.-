package com.assistant.core.services

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PersonalJarvisBridgeProtocolTest {
    private fun request(stream: Boolean = false) = JSONObject()
        .put("model", PersonalJarvisBridgeProtocol.MODEL)
        .put("stream", stream)
        .put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", "Provide concise replies."))
            .put(JSONObject().put("role", "user").put("content", "Question one"))
            .put(JSONObject().put("role", "assistant").put("content", "Answer one"))
            .put(JSONObject().put("role", "user").put("content", "Question two")))
        .toString()

    @Test fun parsesSystemInstructionsAndConversationWithoutDeviceActions() {
        val parsed = PersonalJarvisBridgeProtocol.parse(request(true))
        assertTrue(parsed.stream)
        assertTrue(parsed.text.contains("Provide concise replies."))
        assertTrue(parsed.text.contains("Question two"))
        assertTrue(parsed.text.contains("never", ignoreCase = true).not() || parsed.text.contains("Never claim"))
        assertEquals(listOf("Question one" to "Answer one"), parsed.history)
    }

    @Test fun rejectsUnknownModelAndNonTextContent() {
        try {
            PersonalJarvisBridgeProtocol.parse(JSONObject(request()).put("model", "wrong").toString())
            fail("Unknown model must be rejected")
        } catch (_: IllegalArgumentException) { }
        val request = JSONObject(request())
        request.put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray())))
        try {
            PersonalJarvisBridgeProtocol.parse(request.toString())
            fail("Non-text input must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun bearerTokenMustMatchExactly() {
        assertTrue(PersonalJarvisBridgeProtocol.authorized("Bearer abc", "abc"))
        assertFalse(PersonalJarvisBridgeProtocol.authorized("Bearer ab", "abc"))
        assertFalse(PersonalJarvisBridgeProtocol.authorized("abc", "abc"))
        assertFalse(PersonalJarvisBridgeProtocol.authorized("Bearer abc", ""))
    }

    @Test fun completionIsOpenAiCompatibleJson() {
        val json = JSONObject(PersonalJarvisBridgeProtocol.completion("Hello", 1))
        assertEquals("personal-jarvis", json.getString("model"))
        assertEquals("Hello", json.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content"))
    }

    @Test fun streamedReplyIsValidServerSentEvents() {
        val events = PersonalJarvisBridgeProtocol.stream("Hello", 2)
        val firstData = events.lineSequence().first { it.startsWith("data: ") }.removePrefix("data: ")
        val first = JSONObject(firstData)
        assertEquals("Hello", first.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("delta").getString("content"))
        assertTrue(events.endsWith("data: [DONE]\n\n"))
    }

    @Test fun modelsListsOnlyPersonalJarvis() {
        val model = JSONObject(PersonalJarvisBridgeProtocol.models()).getJSONArray("data").getJSONObject(0)
        assertEquals("personal-jarvis", model.getString("id"))
    }
}
