package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class ConversationMessagesTest {
    @Test fun backgroundSpeechRemainsConversation() {
        assertEquals("You: What time is it?", ConversationMessages.chatMessage("[BG] You: What time is it?"))
        assertEquals("J.A.R.V.I.S.: It is noon.", ConversationMessages.chatMessage("[BG] J.A.R.V.I.S.: It is noon."))
    }

    @Test fun microphoneTrafficCannotFillConversation() {
        val messages = listOf("You: Hello", "J.A.R.V.I.S.: Hello") +
            List(1000) { "[BG] Sherpa audio active: peak 10%, chunks 30." } +
            listOf("[WAKE] decoder ready", "[VOICE] service started")
        assertEquals(listOf("You: Hello", "J.A.R.V.I.S.: Hello"), messages.mapNotNull(ConversationMessages::chatMessage))
    }
}
