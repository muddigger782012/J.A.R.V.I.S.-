package com.assistant.core.services

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import java.util.Locale

data class VoiceRecognitionResult(
    val transcript: String,
    val confidence: Float,
    val alternatives: List<String>,
    val sourceEngine: VoiceAssistantService.HotwordEngine
)

class VoiceAssistantService(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onHotwordDetected: () -> Unit,
    private val onCommandDetected: (VoiceRecognitionResult) -> Unit
) : RecognitionListener, TextToSpeech.OnInitListener {

    private enum class RecognitionMode {
        FALLBACK_SPEECH_HOTWORD,
        COMMAND
    }

    enum class HotwordEngine {
        DEDICATED_OFFLINE,
        SPEECH_FALLBACK
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mode: RecognitionMode = RecognitionMode.FALLBACK_SPEECH_HOTWORD
    private var voiceActive = false
    private var listeningWithSpeechRecognizer = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech = TextToSpeech(context, this)
    private var ttsReady = false
    private var porcupineManager: PorcupineManager? = null
    private var lastWakeEngineUsed = HotwordEngine.SPEECH_FALLBACK
    private var fallbackErrorStreak = 0
    private var fallbackStatusAnnounced = false
    private var config: VoiceConfig = VoiceConfig(
        enableDedicatedWakeWord = true,
        porcupineAccessKey = "",
        wakeSensitivity = 0.6f,
        autoStartVoice = false,
        preferOfflineCommandRecognition = true,
        commandConfidenceThreshold = 0.58f,
        defaultProjectName = "assistant_demo",
        useForegroundServiceMode = false,
        autoStartForegroundService = false,
        customProjectPhrases = "",
        customStatusPhrases = "",
        customShellPhrases = "",
        customRebootPhrases = "",
        customSettingsPhrases = "",
        customStartVoicePhrases = "",
        customStopVoicePhrases = ""
    )
    private var currentHotwordEngine = HotwordEngine.SPEECH_FALLBACK

    fun isRecognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun getCurrentHotwordEngine(): HotwordEngine = currentHotwordEngine

    fun updateConfig(newConfig: VoiceConfig) {
        config = newConfig
        if (voiceActive) {
            // Reboot active listeners so changed engine / sensitivity applies immediately.
            stopListening()
            startHotwordLoop()
        }
    }

    fun startHotwordLoop() {
        voiceActive = true
        mode = RecognitionMode.FALLBACK_SPEECH_HOTWORD
        mainHandler.removeCallbacksAndMessages(null)
        fallbackErrorStreak = 0
        fallbackStatusAnnounced = false

        if (shouldUseDedicatedWakeWord()) {
            startDedicatedWakeWordEngine()
        } else {
            switchToSpeechHotwordFallback("Dedicated wake-word is disabled or not configured.")
        }
    }

    fun stopListening() {
        voiceActive = false
        listeningWithSpeechRecognizer = false
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.stopListening()
        speechRecognizer?.cancel()
        stopDedicatedWakeWordEngine()
        onStatus("Voice listener stopped.")
    }

    fun shutdown() {
        stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts.stop()
        tts.shutdown()
    }

    fun speak(text: String) {
        if (ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, "jarvis-response")
        }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            applyJarvisStyleVoiceProfile()
        }
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit

    override fun onBeginningOfSpeech() = Unit

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        listeningWithSpeechRecognizer = false
    }

    override fun onError(error: Int) {
        listeningWithSpeechRecognizer = false
        if (!voiceActive) return

        when (mode) {
            RecognitionMode.FALLBACK_SPEECH_HOTWORD -> scheduleFallbackAfterError(error)
            RecognitionMode.COMMAND -> {
                onStatus("Command capture error. Returning to wake-word listening.")
                resumeHotwordEngineAfterCommand()
            }
        }
    }

    override fun onResults(results: Bundle?) {
        listeningWithSpeechRecognizer = false
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val confidenceScores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        when (mode) {
            RecognitionMode.FALLBACK_SPEECH_HOTWORD -> handleFallbackHotwordMatches(matches)
            RecognitionMode.COMMAND -> handleCommandMatches(matches, confidenceScores)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (mode != RecognitionMode.FALLBACK_SPEECH_HOTWORD) return
        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        if (containsJarvis(partial)) {
            speechRecognizer?.cancel()
            listeningWithSpeechRecognizer = false
            onWakeWordDetected()
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun shouldUseDedicatedWakeWord(): Boolean {
        return config.enableDedicatedWakeWord && config.porcupineAccessKey.isNotBlank()
    }

    private fun startDedicatedWakeWordEngine() {
        stopDedicatedWakeWordEngine()
        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(config.porcupineAccessKey)
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .setSensitivity(config.wakeSensitivity.coerceIn(0.1f, 1.0f))
                .setErrorCallback { error ->
                    mainHandler.post {
                        onStatus("Dedicated wake-word error: ${error.message ?: "unknown error"}")
                        if (voiceActive) {
                            switchToSpeechHotwordFallback("Switching to speech fallback hotword.")
                        }
                    }
                }
                .build(context) {
                    mainHandler.post {
                        onWakeWordDetected()
                    }
                }
            porcupineManager?.start()
            currentHotwordEngine = HotwordEngine.DEDICATED_OFFLINE
            onStatus("Dedicated wake-word engine active (offline model).")
        } catch (error: PorcupineException) {
            switchToSpeechHotwordFallback(
                "Dedicated wake-word unavailable: ${error.message ?: "setup failed"}"
            )
        } catch (error: Throwable) {
            switchToSpeechHotwordFallback(
                "Dedicated wake-word unavailable: ${error.message ?: "setup failed"}"
            )
        }
    }

    private fun stopDedicatedWakeWordEngine() {
        try {
            porcupineManager?.stop()
        } catch (_: Throwable) {
            // no-op
        }
        porcupineManager?.delete()
        porcupineManager = null
    }

    private fun switchToSpeechHotwordFallback(reason: String) {
        currentHotwordEngine = HotwordEngine.SPEECH_FALLBACK
        onStatus("$reason Using speech fallback wake-word mode.")
        if (!isRecognitionAvailable()) {
            onStatus("Speech recognition is unavailable on this device.")
            return
        }
        mode = RecognitionMode.FALLBACK_SPEECH_HOTWORD
        scheduleFallbackHotwordListening(1500)
    }

    private fun onWakeWordDetected() {
        if (!voiceActive) return
        fallbackErrorStreak = 0
        lastWakeEngineUsed = currentHotwordEngine
        onHotwordDetected()
        speak("Yes?")
        if (currentHotwordEngine == HotwordEngine.DEDICATED_OFFLINE) {
            stopDedicatedWakeWordEngine()
        }
        startCommandListening()
    }

    private fun startCommandListening() {
        if (!isRecognitionAvailable()) {
            onStatus("Speech recognition is unavailable for command capture.")
            resumeHotwordEngineAfterCommand()
            return
        }
        mode = RecognitionMode.COMMAND
        startSpeechRecognizer(
            prompt = "Listening for command",
            preferOffline = config.preferOfflineCommandRecognition
        )
    }

    private fun handleFallbackHotwordMatches(matches: List<String>) {
        if (!voiceActive) return
        if (containsJarvis(matches)) {
            onWakeWordDetected()
        } else {
            fallbackErrorStreak = 0
            scheduleFallbackHotwordListening(6000)
        }
    }

    private fun handleCommandMatches(matches: List<String>, confidenceScores: FloatArray?) {
        if (!voiceActive) return
        val command = matches.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (command.isNullOrBlank()) {
            onStatus("No command detected after hotword.")
        } else {
            onCommandDetected(
                VoiceRecognitionResult(
                    transcript = command,
                    confidence = normalizeConfidence(confidenceScores?.getOrNull(0)),
                    alternatives = matches.filter { it.isNotBlank() },
                    sourceEngine = lastWakeEngineUsed
                )
            )
        }
        resumeHotwordEngineAfterCommand()
    }

    private fun resumeHotwordEngineAfterCommand() {
        if (!voiceActive) return
        if (shouldUseDedicatedWakeWord()) {
            startDedicatedWakeWordEngine()
        } else {
            mode = RecognitionMode.FALLBACK_SPEECH_HOTWORD
            scheduleFallbackHotwordListening(5000)
        }
    }

    private fun containsJarvis(phrases: List<String>): Boolean {
        return phrases.any { it.lowercase(Locale.US).contains("jarvis") }
    }

    private fun getOrCreateRecognizer(): SpeechRecognizer {
        val current = speechRecognizer
        if (current != null) return current
        return SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(this)
            speechRecognizer = it
        }
    }

    private fun scheduleFallbackHotwordListening(delayMs: Long) {
        if (!voiceActive || mode != RecognitionMode.FALLBACK_SPEECH_HOTWORD) return
        val safeDelay = delayMs.coerceAtLeast(2500L)
        mainHandler.postDelayed(
            {
                startSpeechRecognizer(
                    prompt = "Say jarvis",
                    preferOffline = false
                )
            },
            safeDelay
        )
    }

    private fun startSpeechRecognizer(prompt: String, preferOffline: Boolean) {
        if (!voiceActive || listeningWithSpeechRecognizer) return
        val recognizer = getOrCreateRecognizer()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }
        listeningWithSpeechRecognizer = true
        recognizer.startListening(intent)
        if (mode == RecognitionMode.FALLBACK_SPEECH_HOTWORD) {
            if (!fallbackStatusAnnounced) {
                onStatus("Speech fallback hotword mode active. Configure Porcupine AccessKey to reduce beeps.")
                fallbackStatusAnnounced = true
            }
        } else {
            onStatus("Hotword detected. Listening for command...")
        }
    }

    private fun scheduleFallbackAfterError(error: Int) {
        fallbackErrorStreak += 1
        val baseDelay = when (error) {
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 7000L
            SpeechRecognizer.ERROR_NO_MATCH -> 6500L
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 12000L
            SpeechRecognizer.ERROR_CLIENT -> 12000L
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 15000L
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> 30000L
            else -> 9000L
        }
        val penaltyDelay = if (fallbackErrorStreak >= 4) 20000L else 0L
        val delay = (baseDelay + penaltyDelay).coerceAtMost(60000L)
        if (fallbackErrorStreak == 4) {
            onStatus("Reducing wake-word retries to avoid constant beeping.")
        }
        scheduleFallbackHotwordListening(delay)
    }

    private fun applyJarvisStyleVoiceProfile() {
        val selectedVoice = chooseBestJarvisLikeVoice(tts.voices)
        if (selectedVoice != null) {
            tts.voice = selectedVoice
            tts.language = selectedVoice.locale
            onStatus("Voice profile active: ${selectedVoice.name}")
        } else {
            // Fall back to a broadly available English voice profile.
            val fallback = Locale.UK
            tts.language = fallback
            onStatus("Voice profile active: ${fallback.displayName}")
        }
        tts.setPitch(0.92f)
        tts.setSpeechRate(0.95f)
    }

    private fun chooseBestJarvisLikeVoice(voices: Set<Voice>?): Voice? {
        if (voices.isNullOrEmpty()) return null
        val candidates = voices
            .filter { !it.isNetworkConnectionRequired }
            .filter { it.locale.language == Locale.ENGLISH.language }
            .filter { voice -> !(voice.features?.contains("notInstalled") == true) }

        if (candidates.isEmpty()) return null

        fun Voice.score(): Int {
            val nameLower = name.lowercase(Locale.US)
            val localeScore = when (locale.country.uppercase(Locale.US)) {
                "GB" -> 300
                "US" -> 180
                else -> 100
            }
            val qualityScore = quality * 2
            val latencyScore = (600 - latency).coerceAtLeast(0)
            val toneHintScore = when {
                nameLower.contains("male") -> 120
                nameLower.contains("m") -> 30
                else -> 0
            }
            return localeScore + qualityScore + latencyScore + toneHintScore
        }

        return candidates.maxByOrNull { it.score() }
    }

    private fun normalizeConfidence(raw: Float?): Float {
        if (raw == null || raw < 0f) return 0.5f
        return raw.coerceIn(0f, 1f)
    }
}
