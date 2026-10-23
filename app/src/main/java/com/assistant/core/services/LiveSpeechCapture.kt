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
    fun stop() {
        generation++
        val previous = recognizer
        recognizer = null
        runCatching { previous?.cancel() }
        runCatching { previous?.destroy() }
    }
    fun listen(preferOffline: Boolean, onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (Int) -> Unit,
               onDeviceOnly: Boolean = false, useProfileHints: Boolean = true,
               onHypotheses: (List<Pair<String, Float>>) -> Unit = {},
               onEngine: (SpeechCaptureRouting.Engine) -> Unit = {},
               requestedEngine: SpeechCaptureRouting.Engine? = null) {
        stop()
        val strictDevice = onDeviceOnly || requestedEngine == SpeechCaptureRouting.Engine.ON_DEVICE
        val selected = SpeechCaptureRouting.choose(preferOffline && context.packageName == "com.assistant.core", onDeviceOnly,
            platform.deviceAvailable(), platform.systemAvailable(), requestedEngine)
        if (selected == null) { onError(SpeechRecognizer.ERROR_CLIENT); return }
        var fallbackUsed = false
        fun startAttempt(provider: SpeechCaptureRouting.Engine, offlineHint: Boolean) {
            stop()
            val current = generation
            try {
                val listener = object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    override fun onPartialResults(partialResults: Bundle?) {
                        if (current == generation) onPartial(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                    }
                    override fun onResults(results: Bundle?) {
                        if (current != generation) return
                        val candidates = SpeechRecognitionCandidates.read(
                            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty(),
                            results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES))
                        val text = candidates.firstOrNull()?.transcript.orEmpty()
                        stop()
                        val completed = generation
                        if (candidates.isNotEmpty()) onHypotheses(candidates.map { it.transcript to if (it.confidenceKnown) it.confidence else -1f })
                        // A caller can cancel or replace capture while reviewing hypotheses.
                        if (generation != completed) return
                        if (text.isBlank()) onError(SpeechRecognizer.ERROR_NO_MATCH) else onResult(text)
                    }
                    override fun onError(error: Int) {
                        if (current != generation) return
                        if (!strictDevice && provider == SpeechCaptureRouting.Engine.ON_DEVICE && !fallbackUsed && platform.systemAvailable() &&
                            error in setOf(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_SERVER)) {
                            fallbackUsed = true
                            startAttempt(SpeechCaptureRouting.Engine.SYSTEM, false)
                        } else { stop(); onError(error) }
                    }
                }
                val engine = platform.create(provider, listener)
                if (current != generation) { runCatching { engine.cancel() }; runCatching { engine.destroy() }; return }
                recognizer = engine
                onEngine(provider)
                if (current != generation) return
                engine.start(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offlineHint)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    VoiceRecognitionHints.apply(context, this, useProfileHints)
                })
            } catch (_: Exception) { if (current == generation) { stop(); onError(SpeechRecognizer.ERROR_CLIENT) } }
        }
        startAttempt(selected, preferOffline || strictDevice)
    }
}
