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
        assertTrue(session.speechFinished(turn))
        assertNotNull(session.listen())
    }
    @Test fun aCancelledTtsCompletionCannotFinishAnotherReplyThatIsNowSpeaking() {
        val session = LiveChatSession()
        session.start()
        val first = session.listen()!!
        assertTrue(session.transcript(first))
        assertTrue(session.response(first))
        assertTrue(session.interrupt())
        val second = session.listen()!!
        assertTrue(session.transcript(second))
        assertTrue(session.response(second))
        assertFalse(session.speechFinished(first))
        assertEquals(LiveChatSession.Phase.SPEAKING, session.phase)
        assertTrue(session.speechFinished(second))
        assertEquals(LiveChatSession.Phase.WAITING, session.phase)
    }

    @Test fun micTapDuringActiveListeningFinishesWithoutInvalidatingTheTranscript() {
        val session = LiveChatSession()
        session.start()
        val turn = session.listen()!!
        assertEquals(LiveChatSession.MicrophoneTap.FINISH_CAPTURE, session.microphoneTapped(true))
        assertEquals(turn, session.token)
        assertEquals(LiveChatSession.Phase.LISTENING, session.phase)
        assertTrue(session.transcript(turn))
    }

    @Test fun micTapWithStalledCaptureRestartsAndRejectsOldCallbacks() {
        val session = LiveChatSession()
        assertEquals(LiveChatSession.MicrophoneTap.IGNORE, session.microphoneTapped(false))
        session.start()
        val stale = session.listen()!!
        assertEquals(LiveChatSession.MicrophoneTap.RESTART_LISTENING, session.microphoneTapped(false))
        assertEquals(LiveChatSession.Phase.WAITING, session.phase)
        assertFalse(session.transcript(stale))
        val current = session.listen()!!
        assertTrue(session.transcript(current))
        assertEquals(LiveChatSession.MicrophoneTap.RESTART_LISTENING, session.microphoneTapped(false))
        assertEquals(LiveChatSession.Phase.WAITING, session.phase)
        assertFalse(session.response(current))
        val speaking = session.listen()!!
        assertTrue(session.transcript(speaking))
        assertTrue(session.response(speaking))
        assertEquals(LiveChatSession.MicrophoneTap.RESTART_LISTENING, session.microphoneTapped(false))
        assertFalse(session.speechFinished(speaking))
        assertNotNull(session.listen())
    }

    @Test fun mutedReplyImmediatelyReturnsToListeningWithoutTtsCallback() {
        val session = LiveChatSession()
        session.start()
        val turn = session.listen()!!
        assertTrue(session.transcript(turn))
        assertTrue(session.response(turn))
        assertTrue(session.silentResponseFinished(turn))
        assertEquals(LiveChatSession.Phase.WAITING, session.phase)
        assertFalse(session.silentResponseFinished(turn))
        assertNotNull(session.listen())
    }
    @Test fun lateMutedReplyCompletionCannotInterruptANewTurn() {
        val session = LiveChatSession()
        session.start()
        val old = session.listen()!!
        assertTrue(session.transcript(old))
        assertTrue(session.response(old))
        assertTrue(session.silentResponseFinished(old))
        val next = session.listen()!!
        assertTrue(session.transcript(next))
        assertFalse(session.silentResponseFinished(old))
        assertEquals(LiveChatSession.Phase.THINKING, session.phase)
    }
    @Test fun staleRepliesAndDuplicateTranscriptsCannotRestartOrOverwriteSession() {
        val session = LiveChatSession()
        session.start()
        val turn = session.listen()!!
        assertTrue(session.transcript(turn))
        assertFalse(session.transcript(turn))
        session.stop()
        assertFalse(session.response(turn))
        assertFalse(session.speechFinished(turn))
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
        assertTrue(session.transcript(turn)); assertTrue(session.response(turn)); session.speechFinished(turn)
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
    @Test fun contextInvalidatedBeforeSpeechRecoversListeningAndRejectsLateReply() {
        val session = LiveChatSession()
        session.start()
        val original = session.listen()!!
        assertTrue(session.transcript(original))
        assertFalse(session.speechFinished(original)) // Still THINKING; no TTS started.
        assertTrue(session.recover(original))
        assertEquals(LiveChatSession.Phase.WAITING, session.phase)
        assertFalse(session.response(original))
        val next = session.listen()!!
        assertTrue(session.transcript(next))
        assertTrue(session.response(next))
        assertTrue(session.speechFinished(next))
        assertEquals(LiveChatSession.Phase.WAITING, session.phase)
    }

    @Test fun replyTimeoutDistinguishesIdleExpiryRetryAndAStaleTimerWithoutLeavingAnActiveStoppedSession() {
        var time = 0L
        val session = LiveChatSession(now = { time })
        session.configureIdleTimeout(60_000)
        session.start(); val expired = session.listen()!!; session.transcript(expired)
        time = 60_000
        assertEquals(LiveChatSession.ReplyRecovery.EXPIRED, session.responseTimedOut(expired))
        assertFalse(session.active); assertFalse(session.response(expired))
        session.start(); val current = session.listen()!!; session.transcript(current)
        assertEquals(LiveChatSession.ReplyRecovery.STALE, session.responseTimedOut(expired))
        assertEquals(LiveChatSession.Phase.THINKING, session.phase)
        time += 30_000
        assertEquals(LiveChatSession.ReplyRecovery.RETRY, session.responseTimedOut(current))
        assertTrue(session.active); assertFalse(session.response(current)); assertNotNull(session.listen())
    }
}
