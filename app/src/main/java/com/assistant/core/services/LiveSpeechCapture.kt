package com.assistant.core.services

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** Foreground, one-turn capture. Every turn owns its recognizer and callbacks. */
class LiveSpeechCapture(private val context: Context,
                        private val platform: SpeechCapturePlatform = AndroidSpeechCapturePlatform(context)) {
    private var generation = 0L
    private var recognizer: SpeechCapturePlatform.Session? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var trace = 0L
    private var finishCapture: (() -> Unit)? = null
    val isActive get() = recognizer != null
    fun finish() { finishCapture?.invoke() }
    fun stop() = release(cancelled = true)
    private fun release(cancelled: Boolean) {
        generation++
        handler.removeCallbacksAndMessages(null)
        val previous = recognizer
        recognizer = null
        finishCapture = null
        runCatching { previous?.cancel() }
        runCatching { previous?.destroy() }
        if (cancelled && previous != null) VoiceSessionDiagnostics.record(VoiceSessionDiagnostics.Stage.CANCELLED, trace)
    }
    fun listen(preferOffline: Boolean, onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (Int) -> Unit,
               onDeviceOnly: Boolean = false, useProfileHints: Boolean = true,
               onHypotheses: (List<Pair<String, Float>>) -> Unit = {},
               onEngine: (SpeechCaptureRouting.Engine) -> Unit = {},
               requestedEngine: SpeechCaptureRouting.Engine? = null,
               onPhase: (VoiceInteractionState) -> Unit = {}) {
        stop()
        trace = VoiceSessionDiagnostics.begin()
        val strictDevice = onDeviceOnly || requestedEngine == SpeechCaptureRouting.Engine.ON_DEVICE
        val selected = SpeechCaptureRouting.choose(preferOffline && context.packageName == "com.assistant.core", onDeviceOnly,
            platform.deviceAvailable(), platform.systemAvailable(), requestedEngine)
        if (selected == null) {
            VoiceSessionDiagnostics.record(VoiceSessionDiagnostics.Stage.ERROR, trace, SpeechRecognizer.ERROR_CLIENT)
            onError(SpeechRecognizer.ERROR_CLIENT); return
        }
        var fallbackUsed = false
        fun startAttempt(provider: SpeechCaptureRouting.Engine, offlineHint: Boolean) {
            stop()
            val current = generation
            val timing = RuntimeSettings.read(context, "voice_settings")
            val window = VoiceCaptureWindow({ android.os.SystemClock.elapsedRealtime() },
                idleMillis = timing.getLong("capture_idle_millis", 30_000).coerceIn(10_000, 120_000),
                maximumMillis = timing.getLong("capture_max_millis", 180_000).coerceIn(60_000, 600_000))
            val currentTrace = trace
            try {
                val listener = object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        if (current != generation) return
                        VoiceSessionDiagnostics.record(VoiceSessionDiagnostics.Stage.CAPTURE_READY, currentTrace)
                        onPhase(VoiceInteractionState.LISTENING)
                    }
                    override fun onBeginningOfSpeech() {
                        if (current != generation) return
                        window.speechStarted()
                        VoiceSessionDiagnostics.record(VoiceSessionDiagnostics.Stage.SPEECH_STARTED, currentTrace)
                        onPhase(VoiceInteractionState.LISTENING)
                    }
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() {
                        if (current != generation) return
                        window.endOfSpeech()
                        VoiceSessionDiagnostics.record(VoiceSessionDiagnostics.Stage.TRANSCRIBING, currentTrace)
                        onPhase(VoiceInteractionState.TRANSCRIBING)
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    override fun onPartialResults(partialResults: Bundle?) {
                        if (current != generation) return
                        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        if (text.isNotBlank()) {
                            window.speechActivity()
                            VoiceSessionDiagnostics.record(VoiceSessionDiagnostics.Stage.PARTIAL, currentTrace)
                        }
                        onPartial(text)
                    }
                    override fun onResults(results: Bundle?) {
                        if (current != generation) return
                        val candidates = SpeechRecognitionCandidates.read(
                            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty(),
                            results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES))
                        val text = candidates.firstOrNull()?.transcript.orEmpty()
                        release(cancelled = false)
                        VoiceSessionDiagnostics.record(VoiceSessionDiagnostics.Stage.PROCESSING, currentTrace)
                        val completed = generation
                        if (candidates.isNotEmpty()) onHypotheses(candidates.map { it.transcript to if (it.confidenceKnown) it.confidence else -1f })
                        // A caller can cancel or replace capture while reviewing hypotheses.
                        if (generation != completed) return
                        if (text.isBlank()) onError(SpeechRecognizer.ERROR_NO_MATCH) else onResult(text)
                    }
                    override fun onError(error: Int) {
                        if (current != generation) return
                        VoiceSessionDiagnostics.record(VoiceSessionDiagnostics.Stage.ERROR, currentTrace, error)
                        if (!strictDevice && provider == SpeechCaptureRouting.Engine.ON_DEVICE && !fallbackUsed && platform.systemAvailable() &&
                            error in setOf(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                                SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                                SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                            fallbackUsed = true
                            startAttempt(SpeechCaptureRouting.Engine.SYSTEM, false)
                        } else { release(cancelled = false); onError(error) }
                    }
                }
                val engine = platform.create(provider, listener)
                if (current != generation) { runCatching { engine.cancel() }; runCatching { engine.destroy() }; return }
                recognizer = engine
                finishCapture = {
                    if (current == generation) {
                        try {
                            engine.finish()
                            if (current == generation) {
                                window.endOfSpeech()
                                VoiceSessionDiagnostics.record(VoiceSessionDiagnostics.Stage.TRANSCRIBING, currentTrace)
                                onPhase(VoiceInteractionState.TRANSCRIBING)
                            }
                        } catch (_: Exception) { listener.onError(SpeechRecognizer.ERROR_CLIENT) }
                    }
                }
                onEngine(provider)
                if (current != generation) return
                engine.start(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offlineHint)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    // These documented hints are provider-dependent; watchdog behavior does not assume they work.
                    val pause = timing
                        .getLong("speech_pause_millis", 2500L).coerceIn(1000L, 10_000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, pause)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, pause)
                    VoiceRecognitionHints.apply(context, this, useProfileHints)
                })
                val watchdog = object : Runnable {
                    override fun run() {
                        if (current != generation) return
                        if (window.expired()) listener.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                        else handler.postDelayed(this, 1000L)
                    }
                }
                if (current == generation) handler.postDelayed(watchdog, 1000L)
            } catch (_: Exception) { if (current == generation) {
                VoiceSessionDiagnostics.record(VoiceSessionDiagnostics.Stage.ERROR, currentTrace, SpeechRecognizer.ERROR_CLIENT)
                release(cancelled = false)
                if (!strictDevice && provider == SpeechCaptureRouting.Engine.ON_DEVICE && !fallbackUsed && platform.systemAvailable()) {
                    fallbackUsed = true; startAttempt(SpeechCaptureRouting.Engine.SYSTEM, false)
                } else onError(SpeechRecognizer.ERROR_CLIENT)
            } }
        }
        startAttempt(selected, preferOffline || strictDevice)
    }
}
