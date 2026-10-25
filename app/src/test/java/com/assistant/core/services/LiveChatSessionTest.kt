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
        val session = LiveChatSession(maxSilentTurns = 3)
        session.start()
        repeat(2) { assertTrue(session.silence(session.listen()!!)) }
        val turn = session.listen()!!
        assertTrue(session.transcript(turn)); assertTrue(session.response(turn)); session.speechFinished()
        repeat(2) { assertTrue(session.silence(session.listen()!!)) }
        assertFalse(session.silence(session.listen()!!))
        assertFalse(session.active)
        assertNull(session.listen())
    }
    @Test fun productionSessionSurvivesThreeSilentTurnsAndRecoverableFailuresUntilIdleExpiry() {
        var time = 0L
        val session = LiveChatSession(now = { time })
        session.start()
        repeat(8) { time += 25_000; assertTrue(session.silence(session.listen()!!)) }
        val turn = session.listen()!!
        assertTrue(session.recover(turn)); assertFalse(session.transcript(turn))
        assertTrue(session.active)
        time = 300_000
        assertNull(session.listen()); assertFalse(session.active)
    }
    @Test fun timedOutReplyAndOldTtsCompletionCannotAdvanceANewerLiveTurn() {
        val session = LiveChatSession()
        session.start(); val old = session.listen()!!
        assertTrue(session.transcript(old)); assertTrue(session.recover(old))
        assertFalse(session.response(old))
        val next = session.listen()!!
        assertTrue(session.transcript(next)); assertTrue(session.response(next))
        assertFalse(session.speechFinished(old))
        assertTrue(session.speechFinished(next))
        session.stop(); assertFalse(session.speechFinished(next))
    }
    @Test fun cancellingALiveTurnKeepsConversationActiveAndRejectsAllItsLateCallbacks() {
        var time = 0L
        val session = LiveChatSession(now = { time })
        session.configureIdleTimeout(120_000)
        session.start(); val turn = session.listen()!!
        assertTrue(session.transcript(turn)); assertTrue(session.response(turn))
        time = 100_000
        assertTrue(session.interrupt()); assertTrue(session.active)
        assertFalse(session.transcript(turn)); assertFalse(session.response(turn)); assertFalse(session.speechFinished(turn))
        val newTurn = session.listen()!!; assertTrue(session.silence(newTurn))
        time = 219_999; assertNotNull(session.listen())
        time = 220_000; assertFalse(session.recover(session.token)); assertFalse(session.active)
        assertFalse(session.interrupt())
    }
}
