package com.assistant.core

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistant.core.services.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic recognizer callbacks exercise the Android capture boundary without recording audio. */
@RunWith(AndroidJUnit4::class)
class VoiceCaptureAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private class Engines(private val device: Boolean = true, private val system: Boolean = true) : SpeechCapturePlatform {
        val sessions = mutableListOf<Capture>()
        override fun deviceAvailable() = device
        override fun systemAvailable() = system
        override fun create(engine: SpeechCaptureRouting.Engine, listener: RecognitionListener): SpeechCapturePlatform.Session =
            Capture(engine, listener).also { sessions.add(it) }
        class Capture(val engine: SpeechCaptureRouting.Engine, val listener: RecognitionListener) : SpeechCapturePlatform.Session {
            var intent: Intent? = null
            var cancelled = false
            var destroyed = false
            var finished = false
            override fun start(intent: Intent) { this.intent = intent }
            override fun cancel() { cancelled = true }
            override fun finish() { finished = true }
            override fun destroy() { destroyed = true }
        }
    }

    private fun results(vararg text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(*text))
        putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(.99f, .24f, .98f))
    }

    @Test fun partialsAndEndOfSpeechDoNotFinalizeAnIncompleteUtterance() {
        instrumentation.runOnMainSync {
            val engines = Engines()
            val capture = LiveSpeechCapture(context, engines)
            val heard = mutableListOf<String>()
            val partials = mutableListOf<String>()
            val phases = mutableListOf<VoiceInteractionState>()
            try {
                capture.listen(true, partials::add, heard::add, { fail("Unexpected error $it") }, onPhase = phases::add)
                val listener = engines.sessions.single().listener
                listener.onReadyForSpeech(Bundle())
                listener.onBeginningOfSpeech()
                listener.onPartialResults(results("private-utterance first clause"))
                listener.onEndOfSpeech()
                listener.onPartialResults(results("private-utterance first clause and a second clause"))
                assertTrue(heard.isEmpty()); assertEquals(2, partials.size)
                assertTrue(capture.isActive)
                assertEquals(VoiceInteractionState.TRANSCRIBING, phases.last())
                listener.onResults(results("private-utterance first clause and a second clause"))
                assertEquals(1, heard.size); assertFalse(capture.isActive)
                assertFalse(VoiceSessionDiagnostics.render().contains("private-utterance"))
            } finally { capture.stop() }
        }
    }

    @Test fun manualFinishWaitsForFinalResultsAndCancellationRejectsEveryOldPhaseCallback() {
        instrumentation.runOnMainSync {
            val engines = Engines()
            val capture = LiveSpeechCapture(context, engines)
            val phases = mutableListOf<VoiceInteractionState>()
            val heard = mutableListOf<String>()
            try {
                capture.listen(true, {}, heard::add, { fail("Old error delivered") }, onPhase = phases::add)
                val session = engines.sessions.single()
                capture.finish(); assertTrue(session.finished); assertTrue(heard.isEmpty())
                capture.stop()
                session.listener.onReadyForSpeech(Bundle())
                session.listener.onEndOfSpeech()
                session.listener.onPartialResults(results("cancelled private speech"))
                session.listener.onResults(results("cancelled private speech"))
                session.listener.onError(SpeechRecognizer.ERROR_CLIENT)
                assertTrue(phases.isEmpty()); assertTrue(heard.isEmpty())
                assertTrue(session.cancelled && session.destroyed)
            } finally { capture.stop() }
        }
    }

    @Test fun offlineCaptureWorksWithoutASystemProviderAndKeepsTranscriptScoresPaired() {
        instrumentation.runOnMainSync {
            val platform = Engines(system = false)
            val capture = LiveSpeechCapture(context, platform)
            val heard = mutableListOf<String>()
            var hypotheses = emptyList<Pair<String, Float>>()
            try {
                capture.listen(true, {}, { heard.add(it) }, { fail("Unexpected error $it") }, onHypotheses = { hypotheses = it })
                val attempt = platform.sessions.single()
                assertEquals(SpeechCaptureRouting.Engine.ON_DEVICE, attempt.engine)
                assertTrue(attempt.intent!!.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
                attempt.listener.onResults(results(" ", " cancel that ", "confirm screen action"))
                assertEquals(listOf("cancel that"), heard)
                assertEquals("cancel that", hypotheses.first().first)
                assertEquals(.24f, hypotheses.first().second, 0f)
                attempt.listener.onResults(results("confirm screen action"))
                assertEquals(1, heard.size)
                assertTrue(attempt.cancelled && attempt.destroyed)
            } finally { capture.stop() }
        }
    }

    @Test fun unavailableOfflineModelFallsBackOnceAndRejectsCallbacksFromTheOldEngine() {
        instrumentation.runOnMainSync {
            val platform = Engines()
            val capture = LiveSpeechCapture(context, platform)
            val modes = mutableListOf<SpeechCaptureRouting.Engine>()
            val heard = mutableListOf<String>()
            val errors = mutableListOf<Int>()
            try {
                capture.listen(true, {}, { heard.add(it) }, { errors.add(it) }, onEngine = { modes.add(it) })
                val offline = platform.sessions.single()
                offline.listener.onError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
                val fallback = platform.sessions.last()
                assertEquals(listOf(SpeechCaptureRouting.Engine.ON_DEVICE, SpeechCaptureRouting.Engine.SYSTEM), modes)
                assertFalse(fallback.intent!!.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
                assertTrue(offline.cancelled && offline.destroyed)
                offline.listener.onResults(results("confirm screen action"))
                offline.listener.onError(SpeechRecognizer.ERROR_SERVER)
                assertTrue(heard.isEmpty()); assertTrue(errors.isEmpty())
                fallback.listener.onError(SpeechRecognizer.ERROR_SERVER)
                assertEquals(listOf(SpeechRecognizer.ERROR_SERVER), errors)
                assertEquals(2, platform.sessions.size)
            } finally { capture.stop() }
        }
    }

    @Test fun explicitPracticeAndImmediateCancellationNeverStartAnUnrequestedProvider() {
        instrumentation.runOnMainSync {
            val platform = Engines()
            val capture = LiveSpeechCapture(context, platform)
            val errors = mutableListOf<Int>()
            try {
                capture.listen(false, {}, { fail("No transcript expected") }, { errors.add(it) }, onDeviceOnly = true)
                val strict = platform.sessions.single()
                assertEquals(SpeechCaptureRouting.Engine.ON_DEVICE, strict.engine)
                assertTrue(strict.intent!!.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
                strict.listener.onError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
                assertEquals(1, platform.sessions.size)
                assertEquals(listOf(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE), errors)
                capture.listen(true, {}, {}, { fail("Default capture failed") }, requestedEngine = SpeechCaptureRouting.Engine.SYSTEM)
                assertEquals(SpeechCaptureRouting.Engine.SYSTEM, platform.sessions.last().engine)
                assertTrue(platform.sessions.last().intent!!.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
                capture.stop()
                val cancelled = platform.sessions.last()
                cancelled.listener.onError(SpeechRecognizer.ERROR_SERVER)
                assertEquals(2, platform.sessions.size)
                capture.listen(true, {}, { fail("Cancelled result delivered") }, { fail("Cancelled error delivered") }, onEngine = { capture.stop() })
                assertNull(platform.sessions.last().intent)
                assertTrue(platform.sessions.last().destroyed)
                capture.listen(true, {}, { fail("Replaced result delivered") }, { fail("Replaced error delivered") }, onHypotheses = { capture.stop() })
                platform.sessions.last().listener.onResults(results(" ", "cancel that"))
            } finally { capture.stop() }
        }
    }

}
