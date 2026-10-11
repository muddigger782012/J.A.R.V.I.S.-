package com.assistant.core.services

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveChatCommandsTest {
    @Test fun startCommandsWorkWithWakePhrasePolitenessAndPunctuation() {
        for (text in listOf("Start live chat", "Hey Jarvis, start live chat!", "Jarvis please begin live conversation.", "OPEN LIVE CHAT PLEASE"))
            assertEquals(LiveChatCommands.Control.START, LiveChatCommands.parse(text))
    }
    @Test fun stopCommandsAreExplicit() {
        for (text in listOf("End live chat", "please stop live chat", "Jarvis, stop conversation."))
            assertEquals(LiveChatCommands.Control.STOP, LiveChatCommands.parse(text))
    }
    @Test fun questionsAndUnrelatedCommandsDoNotControlSessions() {
        for (text in listOf("How do I start live chat?", "Open maps", "Tell me about live chat", "stop", ""))
            assertEquals(LiveChatCommands.Control.NONE, LiveChatCommands.parse(text))
    }
}
