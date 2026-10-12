package com.assistant.core.services

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LiveChatHistoryTest {
    private fun row(session: String, conversation: String, text: String, timestamp: Long = 1000) =
        JSONObject().put("session", session).put("conversation", conversation).put("text", text).put("createdAt", timestamp)
    @Test fun repeatedLiveSessionsInOneConversationStaySeparateAndKeepTurnOrder() {
        val log = JSONArray().put(row("a", "one", "You: first"))
            .put(row("a", "one", "J.A.R.V.I.S.: first reply"))
            .put(row("b", "one", "You: second"))
            .put(row("b", "one", "J.A.R.V.I.S.: second reply"))
        val sessions = LiveChatHistory.sessions(log)
        assertEquals(listOf("b", "a"), sessions.map { it.id })
        assertEquals(listOf("You: first", "J.A.R.V.I.S.: first reply"), sessions.last().entries.map { it.text })
    }
    @Test fun olderLogsRemainReadableWithoutInventingTimestamps() {
        val log = JSONArray().put(JSONObject().put("conversation", "old").put("text", "You: old question"))
            .put("malformed").put(JSONObject().put("text", ""))
        val session = LiveChatHistory.sessions(log).single()
        assertEquals("legacy:old", session.id)
        assertEquals(0L, session.startedAt)
        assertEquals("Time unavailable", LiveChatHistory.time(session.startedAt))
    }
    @Test fun debugCopyKeepsDialogueAndDistinguishesCurrentSettingsFromHistory() {
        val sessions = LiveChatHistory.sessions(JSONArray().put(row("a", "one", "You: copied text")))
        val text = LiveChatHistory.debugText(sessions, "3.5.test", "Test Android", VoiceResponseOptions(false, true, true))
        assertTrue(text.contains("You: copied text"))
        assertTrue(text.contains("App: 3.5.test"))
        assertTrue(text.contains("regular speech=false; live speech=true; live dialogue=true"))
        assertTrue(text.contains("historical settings were not recorded"))
        assertFalse(text.contains("API key"))
    }
}
