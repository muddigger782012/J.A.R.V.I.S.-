package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class LiveChatSessionTest {
    @Test fun nextTurnWaitsForSpokenReplyToFinish() {
        val session = LiveChatSession()
        session.start()
        val turn = session.listen()!!
        assertTrue(session.transcript(turn))
        assertNull(session.listen())
        assertTrue(session.response(turn))
        assertNull(session.listen())
        assertTrue(session.speechFinished())
        assertNotNull(session.listen())
    }
    @Test fun staleRepliesAndDuplicateTranscriptsCannotRestartOrOverwriteSession() {
        val session = LiveChatSession()
        session.start()
        val turn = session.listen()!!
        assertTrue(session.transcript(turn))
        assertFalse(session.transcript(turn))
        session.stop()
        assertFalse(session.response(turn))
        assertFalse(session.speechFinished())
        session.start()
        session.listen()
        assertFalse(session.response(turn))
        assertFalse(session.transcript(turn))
    }
    @Test fun silenceHasBoundedRetriesAndSuccessfulSpeechResetsIdleCounter() {
        val session = LiveChatSession()
        session.start()
        repeat(2) { assertTrue(session.silence(session.listen()!!)) }
        val turn = session.listen()!!
        assertTrue(session.transcript(turn)); assertTrue(session.response(turn)); session.speechFinished()
        repeat(2) { assertTrue(session.silence(session.listen()!!)) }
        assertFalse(session.silence(session.listen()!!))
        assertFalse(session.active)
        assertNull(session.listen())
    }
}
