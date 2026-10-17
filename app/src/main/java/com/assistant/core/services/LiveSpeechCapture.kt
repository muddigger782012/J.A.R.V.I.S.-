package com.assistant.core.services

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** Foreground, one-turn capture. Every turn owns its recognizer and callbacks. */
class LiveSpeechCapture(private val context: Context) {
    private var generation = 0L
    private var recognizer: SpeechRecognizer? = null
    fun stop() {
        generation++
        val previous = recognizer
        recognizer = null
        runCatching { previous?.cancel() }
        runCatching { previous?.destroy() }
    }
    fun listen(preferOffline: Boolean, onPartial: (String) -> Unit, onResult: (String) -> Unit, onError: (Int) -> Unit,
               onDeviceOnly: Boolean = false, useProfileHints: Boolean = true,
               onHypotheses: (List<Pair<String, Float>>) -> Unit = {}) {
        stop()
        val current = generation
        val available = if (onDeviceOnly) android.os.Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context) else SpeechRecognizer.isRecognitionAvailable(context)
        if (!available) { onError(SpeechRecognizer.ERROR_CLIENT); return }
        try {
            val engine = if (onDeviceOnly && android.os.Build.VERSION.SDK_INT >= 31)
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else if (onDeviceOnly) { onError(SpeechRecognizer.ERROR_CLIENT); return }
            else SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = engine
            engine.setRecognitionListener(object : RecognitionListener {
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
                    val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        .mapIndexed { index, text -> text.trim() to
                            (results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.getOrNull(index) ?: -1f) }
                        .filter { it.first.isNotBlank() }.distinctBy { it.first }.take(5)
                    val text = candidates.firstOrNull()?.first.orEmpty()
                    if (candidates.isNotEmpty()) onHypotheses(candidates)
                    stop()
                    if (text.isBlank()) onError(SpeechRecognizer.ERROR_NO_MATCH) else onResult(text)
                }
                override fun onError(error: Int) {
                    if (current != generation) return
                    stop(); onError(error)
                }
            })
            engine.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                VoiceRecognitionHints.apply(context, this, useProfileHints)
            })
        } catch (_: Exception) { stop(); onError(SpeechRecognizer.ERROR_CLIENT) }
    }
}
