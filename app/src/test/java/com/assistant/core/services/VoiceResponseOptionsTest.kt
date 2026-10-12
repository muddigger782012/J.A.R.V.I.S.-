package com.assistant.core.services

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VoiceResponseOptionsTest {
    @Test fun absentSettingsPreserveSpeechAndDoNotIntroducePopups() {
        assertEquals(VoiceResponseOptions(true, true, false), VoiceResponseOptions.fromJson(JSONObject()))
    }
    @Test fun regularAndLiveSpeechAreIndependentForEveryCombination() {
        for (regular in listOf(false, true)) for (live in listOf(false, true)) for (popup in listOf(false, true)) {
            val options = VoiceResponseOptions(regular, live, popup)
            val restored = VoiceResponseOptions.fromJson(JSONObject(options.toJson().toString()))
            assertEquals(options, restored)
            assertEquals(regular, restored.allowsSpeech(SpeechChannel.REGULAR))
            assertEquals(live, restored.allowsSpeech(SpeechChannel.LIVE_CHAT))
        }
    }
    @Test fun mutedLiveReplyCanCompleteAndListenForTheNextTurn() {
        val session = LiveChatSession()
        session.start()
        val turn = session.listen()!!
        assertTrue(session.transcript(turn))
        assertTrue(session.response(turn))
        assertFalse(VoiceResponseOptions(liveSpeech = false).allowsSpeech(SpeechChannel.LIVE_CHAT))
        assertTrue(session.speechFinished())
        assertNotNull(session.listen())
        assertFalse(session.response(turn))
    }
}
