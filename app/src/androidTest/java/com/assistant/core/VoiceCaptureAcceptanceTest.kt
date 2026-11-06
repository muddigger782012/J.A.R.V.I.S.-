package com.assistant.core

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.ParcelFileDescriptor
import android.media.AudioFormat
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

    @Test fun manualMicBeforeSpeechIsNotMistakenForFinishingARecordedUtterance() {
        instrumentation.runOnMainSync {
            val platform = Engines()
            val capture = LiveSpeechCapture(context, platform)
            try {
                capture.listen(true, {}, {}, { fail("Unexpected capture failure") })
                val listener = platform.sessions.single().listener
                assertTrue(capture.isActive)
                assertFalse(capture.hasSpeechActivity)
                listener.onReadyForSpeech(Bundle())
                assertFalse(capture.hasSpeechActivity)
                listener.onPartialResults(results("Some useful words"))
                assertTrue(capture.hasSpeechActivity)
                listener.onError(SpeechRecognizer.ERROR_NO_MATCH)
                assertFalse(capture.hasSpeechActivity)
                assertFalse(capture.isActive)
            } finally { capture.stop() }
        }
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
                repeat(100) { listener.onPartialResults(results("private-utterance long continuation")) }
                listener.onResults(results("private-utterance first clause and a second clause"))
                assertEquals(1, heard.size); assertFalse(capture.isActive)
                assertFalse(VoiceSessionDiagnostics.render().contains("private-utterance"))
                val directory = java.io.File(context.cacheDir, "voice-observation-test").apply { mkdirs() }
                val scoped = object : android.content.ContextWrapper(context) {
                    override fun getFilesDir(): java.io.File = directory
                }
                try {
                    VoiceRuntimeObservation.write(scoped, VoiceRuntimeObservation.Owner.LOCAL,
                        VoiceAssistantService.WakeState.STOPPED, VoiceInteractionState.IDLE)
                    val reading = VoiceRuntimeObservation.read(scoped, VoiceRuntimeObservation.Owner.LOCAL)
                    assertNotNull(reading)
                    val stages = reading!!.timings.map { it.stage }
                    assertTrue(stages.size <= 16)
                    assertEquals(VoiceSessionDiagnostics.Stage.CAPTURE_REQUESTED, stages.first())
                    assertTrue(stages.contains(VoiceSessionDiagnostics.Stage.CAPTURE_READY))
                    assertTrue(stages.contains(VoiceSessionDiagnostics.Stage.PROCESSING))
                    val stored = java.io.File(directory, "voice-runtime-local.json").readText()
                    assertFalse(stored.contains("private-utterance"))
                    assertTrue(stored.toByteArray().size <= 4096)
                } finally { directory.deleteRecursively() }
            } finally { capture.stop() }
        }
    }
    @Test fun busyOnDeviceProviderReleasesItsCaptureBeforeOneSystemRetryAndCannotDeliverOldResults() {
        instrumentation.runOnMainSync {
            val engines = Engines()
            val capture = LiveSpeechCapture(context, engines)
            val heard = mutableListOf<String>()
            try {
                capture.listen(true, {}, heard::add, { fail("Unexpected terminal failure") })
                val first = engines.sessions.single()
                first.listener.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
                assertTrue(first.cancelled && first.destroyed)
                assertEquals(2, engines.sessions.size)
                assertEquals(SpeechCaptureRouting.Engine.SYSTEM, engines.sessions.last().engine)
                first.listener.onResults(results("old private result"))
                assertTrue(heard.isEmpty())
                engines.sessions.last().listener.onResults(results("current result"))
                assertEquals(listOf("current result"), heard)
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
                assertEquals(listOf(VoiceInteractionState.TRANSCRIBING), phases)
                val beforeCancellation = phases.toList()
                capture.stop()
                session.listener.onReadyForSpeech(Bundle())
                session.listener.onEndOfSpeech()
                session.listener.onPartialResults(results("cancelled private speech"))
                session.listener.onResults(results("cancelled private speech"))
                session.listener.onError(SpeechRecognizer.ERROR_CLIENT)
                assertEquals(beforeCancellation, phases); assertTrue(heard.isEmpty())
                assertTrue(session.cancelled && session.destroyed)
            } finally { capture.stop() }
        }
    }

    @Test fun aSecondMicTapRecoversFromFinishingAndRejectsTheOldTranscript() {
        instrumentation.runOnMainSync {
            val engines = Engines()
            val capture = LiveSpeechCapture(context, engines)
            val heard = mutableListOf<String>()
            try {
                // LiveChatSession's token transitions are covered by JVM tests.
                // Exercise the real Android capture boundary here; minified
                // voiceCheck APKs can rewrite Kotlin default constructors.
                capture.listen(true, {}, heard::add, { fail("Unexpected initial capture error $it") })
                val stale = engines.sessions.single()
                assertTrue(capture.isActive)
                assertFalse(capture.isFinishing)
                capture.finish()
                assertTrue(stale.finished)
                assertTrue(capture.isActive)
                assertTrue(capture.isFinishing)

                // A second mic tap observes isFinishing and chooses restart,
                // rather than silently repeating stopListening().
                capture.stop()
                assertFalse(capture.isFinishing)
                assertFalse(capture.isActive)
                assertTrue(stale.cancelled && stale.destroyed)
                stale.listener.onResults(results("stale private speech"))
                assertTrue(heard.isEmpty())

                capture.listen(true, {}, heard::add, { fail("Unexpected restarted capture error $it") })
                val restarted = engines.sessions.last()
                assertFalse(capture.isFinishing)
                assertTrue(capture.isActive)
                restarted.listener.onResults(results("new voice command"))
                assertEquals(listOf("new voice command"), heard)
                assertFalse(capture.isActive)
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

    @Test fun liveChatKeepsLongerPauseHintsAndRescuesInterruptedSpeech() {
        instrumentation.runOnMainSync {
            val engines = Engines()
            val capture = LiveSpeechCapture(context, engines)
            val heard = mutableListOf<String>()
            val errors = mutableListOf<Int>()
            try {
                capture.listen(true, {}, heard::add, errors::add,
                    minimumSpeechPauseMillis = 3600L, minimumCaptureMaximumMillis = 600_000L,
                    fallbackOnSilence = false, recoverPartialOnFinalFailure = true)
                val active = engines.sessions.single()
                val intent = active.intent!!
                assertEquals(3600L, intent.getLongExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, -1L))
                assertEquals(4350L, intent.getLongExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, -1L))
                active.listener.onBeginningOfSpeech()
                active.listener.onPartialResults(results("Could you explain that again after my pause"))
                active.listener.onError(SpeechRecognizer.ERROR_NO_MATCH)
                assertEquals(listOf("Could you explain that again after my pause"), heard)
                assertTrue(errors.isEmpty())
                assertFalse(capture.isActive)
                active.listener.onResults(results("stale speech must not reach conversation"))
                assertEquals(1, heard.size)
            } finally { capture.stop() }
        }
    }

    @Test fun liveChatNeverUsesPartialSpeechToApproveSensitiveActions() {
        instrumentation.runOnMainSync {
            val engines = Engines()
            val capture = LiveSpeechCapture(context, engines)
            val heard = mutableListOf<String>()
            val errors = mutableListOf<Int>()
            try {
                capture.listen(true, {}, heard::add, errors::add,
                    minimumSpeechPauseMillis = 3600L, fallbackOnSilence = false,
                    recoverPartialOnFinalFailure = true)
                val active = engines.sessions.single()
                active.listener.onBeginningOfSpeech()
                active.listener.onPartialResults(results("Please approve the installation now"))
                active.listener.onError(SpeechRecognizer.ERROR_NO_MATCH)
                assertTrue(heard.isEmpty())
                assertEquals(listOf(SpeechRecognizer.ERROR_NO_MATCH), errors)
            } finally { capture.stop() }
        }
    }

    @Test fun experimentalPcmUsesOneExternalAudioSourceAndNeverStartsASecondRecognizerOnError() {
        instrumentation.runOnMainSync {
            if (Build.VERSION.SDK_INT < 33) return@runOnMainSync
            val platform = Engines()
            val capture = LiveSpeechCapture(context, platform)
            val pipe = ParcelFileDescriptor.createPipe()
            val errors = mutableListOf<Int>()
            try {
                capture.listen(true, {}, { fail("No result expected") }, errors::add,
                    externalAudioSource = pipe[0])
                assertEquals(1, platform.sessions.size)
                val selected = platform.sessions.single()
                val intent = requireNotNull(selected.intent)
                assertNotNull(intent.getParcelableExtra<ParcelFileDescriptor>(
                    RecognizerIntent.EXTRA_AUDIO_SOURCE))
                assertEquals(1, intent.getIntExtra(
                    RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, -1))
                assertEquals(16_000, intent.getIntExtra(
                    RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, -1))
                assertEquals(AudioFormat.ENCODING_PCM_16BIT, intent.getIntExtra(
                    RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, -1))
                selected.listener.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
                assertEquals(listOf(SpeechRecognizer.ERROR_RECOGNIZER_BUSY), errors)
                assertEquals(1, platform.sessions.size)
                assertTrue(selected.cancelled && selected.destroyed)
                selected.listener.onResults(results("stale private speech"))
            } finally {
                capture.stop()
                runCatching { pipe[0].close() }
                runCatching { pipe[1].close() }
            }
        }
    }

}
