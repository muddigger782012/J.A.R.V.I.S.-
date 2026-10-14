package com.assistant.core.services

import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
    private val onCommandDetected: (VoiceRecognitionResult) -> Unit,
    private val onSpeechStarted: () -> Unit = {},
    private val onSpeechFinished: () -> Unit = {}
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
    private var commandFromWake = false
    private var listeningWithSpeechRecognizer = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech = TextToSpeech(context, this)
    private var ttsReady = false
    private var speechVoiceUsable = false
    private var speechShutdown = false
    private var lastSpeechVoiceStatus = ""
    private var activeSpeechChannel = SpeechChannel.REGULAR
    private val responseReceiver = object : BroadcastReceiver() {
        override fun onReceive(receivedContext: Context?, intent: Intent?) {
            if (intent?.action == AssistantVoicePreferences.ACTION_CHANGED && ttsReady && activeSpeechPrefix == null) {
                applyAssistantVoiceProfile()
            }
            if (activeSpeechPrefix != null && !VoiceResponsePreferences.load(context).allowsSpeech(activeSpeechChannel)) {
                val next = afterSpeech
                stopSpeaking()
                next?.invoke() ?: onSpeechFinished()
            }
        }
    }
    private val speechPreferences = AssistantVoicePreferences(context)
    init {
        val changes = IntentFilter(VoiceResponsePreferences.ACTION_CHANGED).apply { addAction(AssistantVoicePreferences.ACTION_CHANGED) }
        ContextCompat.registerReceiver(context, responseReceiver, changes, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    private var wakeGeneration = 0L
    private var sherpaWakeWordEngine: SherpaWakeWordEngine? = null
    private var lastWakeEngineUsed = HotwordEngine.SPEECH_FALLBACK
    private var fallbackErrorStreak = 0
    private var fallbackStatusAnnounced = false
    private var config: VoiceConfig = VoiceConfig(
        enableDedicatedWakeWord = true,
        wakeWord = "hey jarvis",
        wakeSensitivity = 0.8f,
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

    fun startPushToTalk() {
        commandFromWake = false
        voiceActive = true
        fallbackErrorStreak = 0
        if (currentHotwordEngine == HotwordEngine.DEDICATED_OFFLINE) stopDedicatedWakeWordEngine()
        startCommandListening()
    }

    fun startHotwordLoop() {
        commandFromWake = false
        voiceActive = true
        mode = RecognitionMode.FALLBACK_SPEECH_HOTWORD
        mainHandler.removeCallbacksAndMessages(null)
        fallbackErrorStreak = 0
        fallbackStatusAnnounced = false

        if (shouldUseDedicatedWakeWord()) {
            startDedicatedWakeWordEngine()
        } else {
            // Android SpeechRecognizer is intentionally NOT used as a
            // continuous wake-word engine. Repeated recognizer sessions cause
            // the system start-listening beep every few seconds and are not a
            // reliable always-on hotword implementation.
            currentHotwordEngine = HotwordEngine.SPEECH_FALLBACK
            onStatus("Wake-word standby requires the dedicated offline wake-word engine. Push-to-talk remains available.")
        }
    }

    fun stopListening() {
        voiceActive = false
        if (afterSpeech != null) stopSpeaking()
        listeningWithSpeechRecognizer = false
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.stopListening()
        speechRecognizer?.cancel()
        stopDedicatedWakeWordEngine()
        onStatus("Voice listener stopped.")
    }

    fun shutdown() {
        speechShutdown = true
        ttsReady = false
        runCatching { context.unregisterReceiver(responseReceiver) }
        stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts.stop()
        tts.shutdown()
        neuralSpeech?.shutdown()
    }

    private var neuralSpeech: NeuralAssistantSpeech? = null
    private var afterSpeech: (() -> Unit)? = null
    private var wakeReplyIndex = 0
    private var speechSequence = 0L
    private var activeSpeechPrefix: String? = null
    private var finalSpeechId: String? = null

    fun stopSpeaking() {
        neuralSpeech?.stop()
        afterSpeech = null
        activeSpeechPrefix = null
        finalSpeechId = null
        tts.stop()
    }

    private fun finishSpeech() {
        activeSpeechPrefix = null
        finalSpeechId = null
        val next = afterSpeech
        afterSpeech = null
        next?.invoke() ?: onSpeechFinished()
    }

    fun speak(text: String, channel: SpeechChannel = SpeechChannel.REGULAR, after: (() -> Unit)? = null) {
        stopSpeaking()
        afterSpeech = after
        activeSpeechChannel = channel
        if (!VoiceResponsePreferences.load(context).allowsSpeech(channel)) {
            // Completion still advances silent live turns and rearms wake capture.
            finishSpeech()
            return
        }
        val selection = speechPreferences.load()
        if (text.isNotBlank() && selection.profile == AssistantVoiceProfile.ORIGINAL && NeuralVoicePack(context).installed()) {
            val prefix = "jarvis-response-${++speechSequence}-"
            activeSpeechPrefix = prefix
            if (after == null) onSpeechStarted()
            val voice = NeuralVoiceCatalog.resolve(selection.originalVoice)
            onStatus("${voice.label} · offline Kokoro")
            val engine = neuralSpeech ?: NeuralAssistantSpeech(context).also { neuralSpeech = it }
            engine.speak(text, voice, cachePhrase = after != null, done = { if (activeSpeechPrefix == prefix) finishSpeech() }, error = { failure ->
                if (activeSpeechPrefix == prefix) {
                    onStatus("Offline male voice failed ($failure). Trying the saved female voice for this reply.")
                    speakAndroidResponse(text, forceFemale = true)
                }
            })
            return
        }
        speakAndroidResponse(text)
    }

    private fun speakAndroidResponse(text: String, forceFemale: Boolean = false) {
        if (!VoiceResponsePreferences.load(context).allowsSpeech(activeSpeechChannel)) { finishSpeech(); return }
        if (!ttsReady || text.isBlank()) {
            if (forceFemale) onStatus("Android speech fallback is not ready. Open Voice diagnostics to inspect the failure.")
            finishSpeech()
            return
        }
        if (forceFemale) {
            val saved = speechPreferences.load()
            val result = AssistantSpeechVoice.apply(tts, AssistantVoiceProfile.FEMALE, saved.femaleVoice, saved.femaleVoice)
            speechVoiceUsable = result.usable
            onStatus("Temporary female fallback: ${result.description}")
        } else applyAssistantVoiceProfile()
        if (!speechVoiceUsable) {
            finishSpeech()
            return
        }
        if (afterSpeech == null) onSpeechStarted()
        val chunks = SpeechChunks.split(text, minOf(3000, TextToSpeech.getMaxSpeechInputLength() - 1))
        val prefix = "jarvis-response-${++speechSequence}-"
        activeSpeechPrefix = prefix
        finalSpeechId = prefix + chunks.lastIndex
        chunks.forEachIndexed { index, chunk ->
            val result = tts.speak(chunk, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, prefix + index)
            if (result == TextToSpeech.ERROR) {
                activeSpeechPrefix = null
                finalSpeechId = null
                tts.stop()
                finishSpeech()
                return
            }
        }
    }

    override fun onInit(status: Int) {
        if (speechShutdown) return
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    mainHandler.post {
                        if (utteranceId == finalSpeechId && finalSpeechId != null) {
                            activeSpeechPrefix = null
                            finalSpeechId = null
                            finishSpeech()
                        }
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    mainHandler.post {
                        if (activeSpeechPrefix != null && utteranceId?.startsWith(activeSpeechPrefix!!) == true) {
                            activeSpeechPrefix = null
                            finalSpeechId = null
                            tts.stop()
                            finishSpeech()
                        }
                    }
                }
            })
            AssistantSpeechVoice.rememberPreviousVoice(tts, speechPreferences)
            applyAssistantVoiceProfile()
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
                val wasWake = commandFromWake
                if (wasWake) commandFromWake = false
                when (error) {
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_NO_MATCH -> {
                        onStatus("No speech detected. Push-to-talk ended.")
                        // A silent push-to-talk session is a normal outcome.
                        // Do not launch/re-arm the background wake engine here.
                        if (wasWake) resumeHotwordEngineAfterCommand() else mode = RecognitionMode.FALLBACK_SPEECH_HOTWORD
                    }
                    else -> {
                        onStatus("Command capture error ($error). Push-to-talk ended.")
                        mode = RecognitionMode.FALLBACK_SPEECH_HOTWORD
                    }
                }
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
        if (containsWakeWord(partial)) {
            speechRecognizer?.cancel()
            listeningWithSpeechRecognizer = false
            onWakeWordDetected()
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun shouldUseDedicatedWakeWord(): Boolean {
        return config.enableDedicatedWakeWord
    }

    private fun startDedicatedWakeWordEngine() {
        stopDedicatedWakeWordEngine()
        if (sherpaWakeWordEngine?.hasLiveWorker() == true) {
            if (sherpaWakeWordEngine?.hasStalled() == true) {
                onStatus("Wake-word native decoder is stalled. Restart the app before retrying.")
            } else {
                mainHandler.postDelayed({ if (voiceActive) startDedicatedWakeWordEngine() }, 100L)
            }
            return
        }
        val phrase = config.wakeWord.trim().ifBlank { "hey jarvis" }
        val generation = ++wakeGeneration
        val engine = SherpaWakeWordEngine(context, phrase, config.wakeSensitivity,
            onDetected = { mainHandler.post { if (generation == wakeGeneration) onWakeWordDetected() } },
            onStatus = { message -> mainHandler.post { if (generation == wakeGeneration) onStatus(message) } },
            onFailure = { mainHandler.post {
                if (generation == wakeGeneration) {
                    currentHotwordEngine = HotwordEngine.SPEECH_FALLBACK
                    onStatus("Dedicated wake-word standby failed. Push-to-talk remains available.")
                }
            } })
        sherpaWakeWordEngine = engine
        if (engine.start()) {
            currentHotwordEngine = HotwordEngine.DEDICATED_OFFLINE
            onStatus("Sherpa-ONNX wake-word engine starting; checking native decoder.")
        } else {
            sherpaWakeWordEngine = null
            switchToSpeechHotwordFallback("Sherpa-ONNX wake-word engine is not ready.")
        }
    }

    private fun stopDedicatedWakeWordEngine() {
        wakeGeneration += 1
        sherpaWakeWordEngine?.stop()
        if (sherpaWakeWordEngine?.hasLiveWorker() != true) sherpaWakeWordEngine = null
    }

    private fun switchToSpeechHotwordFallback(reason: String) {
        currentHotwordEngine = HotwordEngine.SPEECH_FALLBACK
        mode = RecognitionMode.FALLBACK_SPEECH_HOTWORD
        // Do not continuously restart Android SpeechRecognizer as a wake detector.
        // It is session-oriented, causes audible beeps on many devices, and is
        // substantially less reliable than a dedicated always-on keyword engine.
        onStatus("$reason Dedicated wake-word standby is unavailable. Push-to-talk remains available.")
    }

    private fun onWakeWordDetected() {
        if (!voiceActive) return
        commandFromWake = true
        fallbackErrorStreak = 0
        lastWakeEngineUsed = currentHotwordEngine
        onHotwordDetected()
        if (currentHotwordEngine == HotwordEngine.DEDICATED_OFFLINE) {
            stopDedicatedWakeWordEngine()
        }
        val token = wakeGeneration
        val reply = AssistantPhrasePreferences.load(context).reply(wakeReplyIndex++)
        speak(reply, after = { if (voiceActive && commandFromWake && wakeGeneration == token) startCommandListening() })
    }

    fun startWithIntroduction() {
        stopListening()
        stopSpeaking()
        voiceActive = true
        val token = wakeGeneration
        speak(AssistantPhrasePreferences.load(context).introduction,
            after = { if (voiceActive && wakeGeneration == token) startHotwordLoop() })
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
        if (containsWakeWord(matches)) {
            onWakeWordDetected()
        } else {
            fallbackErrorStreak = 0
            scheduleFallbackHotwordListening(6000)
        }
    }

    fun rearmAfterResponse() {
        if (commandFromWake && voiceActive) {
            commandFromWake = false
            resumeHotwordEngineAfterCommand()
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
        // Push-to-talk is a one-shot capture. Background wake listening has
        // its own service lifecycle and must not be started as a side effect.
        mode = RecognitionMode.FALLBACK_SPEECH_HOTWORD
    }

    private fun resumeHotwordEngineAfterCommand() {
        if (!voiceActive) return
        if (shouldUseDedicatedWakeWord()) {
            startDedicatedWakeWordEngine()
        } else {
            mode = RecognitionMode.FALLBACK_SPEECH_HOTWORD
            onStatus("Wake-word standby unavailable; use push-to-talk until the dedicated engine is configured.")
        }
    }

    private fun containsWakeWord(phrases: List<String>): Boolean {
        val wakeWord = config.wakeWord.trim().lowercase(Locale.US).ifBlank { "hey jarvis" }
        return phrases.any { it.lowercase(Locale.US).contains(wakeWord) }
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
                    prompt = "Say ${config.wakeWord.trim().ifBlank { "hey jarvis" }}",
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
            if (mode == RecognitionMode.COMMAND) VoiceRecognitionHints.apply(context, this)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }
        listeningWithSpeechRecognizer = true
        recognizer.startListening(intent)
        if (mode == RecognitionMode.FALLBACK_SPEECH_HOTWORD) {
            if (!fallbackStatusAnnounced) {
                onStatus("Speech fallback hotword mode active.")
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

    private fun applyAssistantVoiceProfile() {
        val saved = speechPreferences.load()
        val profile = saved.profile
        val result = if (profile == AssistantVoiceProfile.ORIGINAL) {
            if (NeuralVoicePack(context).installed()) AssistantSpeechVoice.Result(true, "Offline Kokoro male voices ready.")
            else AssistantSpeechVoice.apply(tts, AssistantVoiceProfile.FEMALE, saved.femaleVoice, saved.femaleVoice).let {
                it.copy(description = (if (NeuralVoicePack(context).bundled()) "Included male voices are preparing. Using Default (Female) temporarily. "
                    else "Offline male voices need downloading in Assistant voice. Using Default (Female) temporarily. ") + it.description)
            }
        } else AssistantSpeechVoice.apply(tts, profile, saved.voiceName(profile), saved.femaleVoice)
        speechVoiceUsable = result.usable
        val message = "${profile.label}: ${result.description}"
        if (message != lastSpeechVoiceStatus) {
            lastSpeechVoiceStatus = message
            onStatus(message)
        }
    }

    private fun normalizeConfidence(raw: Float?): Float {
        if (raw == null || raw < 0f) return 0.5f
        return raw.coerceIn(0f, 1f)
    }
}
