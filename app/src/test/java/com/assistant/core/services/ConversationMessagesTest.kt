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

    @Test fun stagePollingAndUpdaterMessagesAreDiagnostics() {
        for (message in listOf(
            "[BG-DIAG] Wake stage: entered-start-command",
            "[BG-DIAG] Wake stage: sherpa-worker-after-is-ready-8495-false",
            "Updater: Downloading latest build...",
            "Updater: Installer opened. Confirm installation to complete update.",
            "Sherpa native decode self-test passed",
            "A future diagnostic with a new prefix"
        )) assertNull(message, ConversationMessages.chatMessage(message))
    }

    @Test fun typedMessagesAndMultilineRepliesArePreserved() {
        assertEquals("You: Hello", ConversationMessages.chatMessage("You: Hello"))
        val reply = "J.A.R.V.I.S.: First line\nSecond line"
        assertEquals(reply, ConversationMessages.chatMessage(reply))
    }
}
