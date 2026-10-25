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
    private val onSpeechFinished: () -> Unit = {},
    private val onCaptureFinished: () -> Unit = {},
    private val onWakeState: (WakeState) -> Unit = {},
    private val onCapturePhase: (VoiceInteractionState) -> Unit = {}
) : RecognitionListener, TextToSpeech.OnInitListener {

    private enum class RecognitionMode {
        FALLBACK_SPEECH_HOTWORD,
        COMMAND
    }

    enum class HotwordEngine {
        DEDICATED_OFFLINE,
        SPEECH_FALLBACK
    }
    enum class WakeState { STOPPED, STARTING, LISTENING, FAILED }
    var wakeState: WakeState = WakeState.STOPPED
        private set
    private var runtimePhase = VoiceInteractionState.IDLE
    private val runtimeOwner get() = if (context is VoiceForegroundService) VoiceRuntimeObservation.Owner.FOREGROUND else VoiceRuntimeObservation.Owner.LOCAL
    private fun observe(phase: VoiceInteractionState = runtimePhase) {
        runtimePhase = phase
        VoiceRuntimeObservation.write(context, runtimeOwner, wakeState, phase)
    }
    private fun wakeState(value: WakeState) {
        if (wakeState == value) return
        wakeState = value
        VoiceSessionDiagnostics.record(when (value) {
            WakeState.STARTING -> VoiceSessionDiagnostics.Stage.WAKE_STARTING
            WakeState.LISTENING -> VoiceSessionDiagnostics.Stage.WAKE_LISTENING
            WakeState.STOPPED -> VoiceSessionDiagnostics.Stage.WAKE_STOPPED
            WakeState.FAILED -> VoiceSessionDiagnostics.Stage.WAKE_FAILED
        })
        observe(if (value == WakeState.LISTENING) VoiceInteractionState.WAKE_LISTENING
            else if (runtimePhase == VoiceInteractionState.WAKE_LISTENING) VoiceInteractionState.IDLE else runtimePhase)
        onWakeState(value)
    }
    private var wakeEnabled = false
    private var wakeRecoveryAttempts = 0
    private fun scheduleWakeRecovery() {
        if (!wakeEnabled || !voiceActive || commandFromWake || commandCapture.isActive || activeSpeechPrefix != null) return
        if (sherpaWakeWordEngine?.hasStalled() == true) {
            onStatus("The native wake decoder is unresponsive. Restart the app before retrying; a second decoder will not be opened.")
            return
        }
        if (wakeRecoveryAttempts >= 3) {
            onStatus("Wake standby recovery exhausted its bounded retries. Push-to-talk remains available; review voice diagnostics.")
            return
        }
        val generation = wakeGeneration
        val delay = listOf(2000L, 5000L, 10000L)[wakeRecoveryAttempts++]
        onStatus("Wake standby failed. Retrying initialization after releasing the previous microphone session.")
        mainHandler.postDelayed({
            if (generation == wakeGeneration && voiceActive && wakeEnabled && !commandCapture.isActive && activeSpeechPrefix == null)
                startDedicatedWakeWordEngine()
        }, delay)
    }
    private val commandCapture = LiveSpeechCapture(context)
    fun isCapturing() = commandCapture.isActive
    fun finishCapture() = commandCapture.finish()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mode: RecognitionMode = RecognitionMode.FALLBACK_SPEECH_HOTWORD
    private var voiceActive = false
    private var commandFromWake = false
    private var listeningWithSpeechRecognizer = false
    private var speechRecognizer: SpeechRecognizer? = null
    private val captures = RecognitionCaptureGate()
    private var captureToken = 0L
    private val screenAccess by lazy { ScreenAccessClient(context) }
    private var captureScreenScope = ""
    private var onDeviceCapture = false
    private var usedSystemFallback = false
    private var legacyCaptureWindow: VoiceCaptureWindow? = null
    private fun resetRecognition() {
        commandCapture.stop()
        captures.cancel(); listeningWithSpeechRecognizer = false
        runCatching { speechRecognizer?.cancel(); speechRecognizer?.destroy() }
        speechRecognizer = null
        legacyCaptureWindow = null
    }
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

    private fun recognitionEngine(preferOffline: Boolean): SpeechCaptureRouting.Engine? {
        val platform = AndroidSpeechCapturePlatform(context)
        return SpeechCaptureRouting.choose(preferOffline && context.packageName == "com.assistant.core", false,
            platform.deviceAvailable(), platform.systemAvailable())
    }

    fun isRecognitionAvailable(): Boolean = recognitionEngine(config.preferOfflineCommandRecognition) != null

    fun getCurrentHotwordEngine(): HotwordEngine = currentHotwordEngine

    fun updateConfig(newConfig: VoiceConfig) {
        if (config == newConfig) return
        val wakeChanged = config.enableDedicatedWakeWord != newConfig.enableDedicatedWakeWord ||
            config.wakeWord != newConfig.wakeWord || config.wakeSensitivity != newConfig.wakeSensitivity
        config = newConfig
        if (voiceActive && wakeEnabled && wakeChanged && !commandCapture.isActive) {
            // Reboot active listeners so changed engine / sensitivity applies immediately.
            stopListening()
            startHotwordLoop()
        }
    }

    fun startPushToTalk(returnToWake: Boolean = wakeEnabled) {
        stopSpeaking(); resetRecognition()
        commandFromWake = returnToWake
        voiceActive = true
        fallbackErrorStreak = 0
        stopDedicatedWakeWordEngine()
        startCommandListening()
    }

    /** One follow-up capture per spoken task review; silence returns to wake standby. */
    fun startFollowUpCapture(returnToWake: Boolean = true) {
        stopSpeaking(); resetRecognition(); stopDedicatedWakeWordEngine()
        voiceActive = true; commandFromWake = returnToWake
        // Match live chat's echo gap so the spoken confirmation cue is not immediately recaptured.
        val scheduled = captures.begin()
        mainHandler.postDelayed({
            if (voiceActive && captures.valid(scheduled)) startCommandListening()
        }, 650L)
    }

    fun startHotwordLoop() {
        if (voiceActive && wakeState == WakeState.LISTENING && sherpaWakeWordEngine?.hasLiveWorker() == true) return
        wakeEnabled = true
        wakeRecoveryAttempts = 0
        resetRecognition()
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
        wakeEnabled = false
        voiceActive = false
        if (afterSpeech != null) stopSpeaking()
        resetRecognition()
        mainHandler.removeCallbacksAndMessages(null)
        stopDedicatedWakeWordEngine()
        observe(VoiceInteractionState.IDLE)
        onStatus("Voice listener stopped.")
    }

    fun shutdown() {
        speechShutdown = true
        ttsReady = false
        runCatching { context.unregisterReceiver(responseReceiver) }
        stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        stopSpeaking()
        tts.shutdown()
        neuralSpeech?.shutdown()
    }

    private var neuralSpeech: NeuralAssistantSpeech? = null
    private var afterSpeech: (() -> Unit)? = null
    private var wakeReplyIndex = 0
    private var speechSequence = 0L
    private var activeSpeechPrefix: String? = null
    private var finalSpeechId: String? = null
    private val audioManager by lazy { context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }
    private var androidSpeechFocus: android.media.AudioFocusRequest? = null
    private fun releaseSpeechFocus() {
        androidSpeechFocus?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        androidSpeechFocus = null
    }

    fun stopSpeaking() {
        if (activeSpeechPrefix != null) observe(VoiceInteractionState.IDLE)
        neuralSpeech?.stop()
        afterSpeech = null
        activeSpeechPrefix = null
        finalSpeechId = null
        tts.stop()
        releaseSpeechFocus()
    }

    private fun finishSpeech() {
        releaseSpeechFocus()
        VoiceSessionDiagnostics.record(VoiceSessionDiagnostics.Stage.IDLE)
        observe(VoiceInteractionState.IDLE)
        activeSpeechPrefix = null
        finalSpeechId = null
        val next = afterSpeech
        afterSpeech = null
        next?.invoke() ?: onSpeechFinished()
    }

    fun speak(text: String, channel: SpeechChannel = SpeechChannel.REGULAR, after: (() -> Unit)? = null) {
        speakPrepared(ChatSearchResults.forSpeech(text), channel, after)
    }

    private fun speakPrepared(text: String, channel: SpeechChannel, after: (() -> Unit)?) {
        stopSpeaking()
        VoiceSessionDiagnostics.record(VoiceSessionDiagnostics.Stage.SPEECH_REQUESTED)
        observe(VoiceInteractionState.SPEAKING)
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
            engine.speak(text, voice, cachePhrase = after != null,
                onAudio = { if (activeSpeechPrefix == prefix) VoiceSessionDiagnostics.record(VoiceSessionDiagnostics.Stage.SPEAKING) },
                done = { if (activeSpeechPrefix == prefix) finishSpeech() }, error = { failure ->
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
        val attributes = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val focus = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0 && activeSpeechPrefix == prefix) {
                    onStatus("Spoken reply interrupted by audio focus loss. The text reply remains available.")
                    tts.stop(); finishSpeech()
                }
            }, mainHandler).build()
        androidSpeechFocus = focus
        if (runCatching { audioManager.requestAudioFocus(focus) }.getOrDefault(android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED) != android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            onStatus("Spoken reply could not acquire audio focus. The text reply remains available.")
            VoiceSessionDiagnostics.record(VoiceSessionDiagnostics.Stage.ERROR)
            finishSpeech(); return
        }
        tts.setAudioAttributes(attributes)
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
                override fun onStart(utteranceId: String?) {
                    mainHandler.post {
                        if (activeSpeechPrefix != null && utteranceId == activeSpeechPrefix + "0")
                            VoiceSessionDiagnostics.record(VoiceSessionDiagnostics.Stage.SPEAKING)
                    }
                }
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

    override fun onBeginningOfSpeech() { legacyCaptureWindow?.speechStarted() }

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        legacyCaptureWindow?.endOfSpeech()
        listeningWithSpeechRecognizer = false
    }

    override fun onError(error: Int) {
        listeningWithSpeechRecognizer = false
        if (!voiceActive) return
        if (mode == RecognitionMode.COMMAND && onDeviceCapture && !usedSystemFallback &&
            error in setOf(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_SERVER)) {
            usedSystemFallback = true
            onStatus("The on-device speech model is unavailable. Trying the system recognizer once; it may require a network connection.")
            startSpeechRecognizer("Listening for command", preferOffline = false)
            return
        }
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
                        if (wasWake) resumeHotwordEngineAfterCommand() else mode = RecognitionMode.FALLBACK_SPEECH_HOTWORD
                    }
                }
            }
        }
        onCaptureFinished()
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
        if (partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.any(String::isNotBlank) == true)
            legacyCaptureWindow?.speechActivity()
        if (mode != RecognitionMode.FALLBACK_SPEECH_HOTWORD) return
        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        if (containsWakeWord(partial)) {
            captures.cancel()
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
                wakeState(WakeState.FAILED)
                onStatus("Wake-word native decoder is stalled. Restart the app before retrying.")
            } else {
                mainHandler.postDelayed({ if (voiceActive) startDedicatedWakeWordEngine() }, 100L)
            }
            return
        }
        val phrase = config.wakeWord.trim().ifBlank { "hey jarvis" }
        val generation = ++wakeGeneration
        wakeState(WakeState.STARTING)
        val engine = SherpaWakeWordEngine(context, phrase, config.wakeSensitivity,
            onDetected = { mainHandler.post { if (generation == wakeGeneration) onWakeWordDetected() } },
            onStatus = { message -> mainHandler.post { if (generation == wakeGeneration) { observe(); onStatus(message) } } },
            onListening = { mainHandler.post { if (generation == wakeGeneration) {
                wakeRecoveryAttempts = 0; wakeState(WakeState.LISTENING)
            } } },
            onFailure = { mainHandler.post {
                if (generation == wakeGeneration) {
                    wakeState(WakeState.FAILED)
                    currentHotwordEngine = HotwordEngine.SPEECH_FALLBACK
                    onStatus("Dedicated wake-word standby failed. Push-to-talk remains available.")
                    scheduleWakeRecovery()
                }
            } })
        sherpaWakeWordEngine = engine
        if (engine.start()) {
            currentHotwordEngine = HotwordEngine.DEDICATED_OFFLINE
            onStatus("Sherpa-ONNX wake-word engine starting; checking native decoder.")
        } else {
            wakeState(WakeState.FAILED)
            sherpaWakeWordEngine = null
            switchToSpeechHotwordFallback("Sherpa-ONNX wake-word engine is not ready.")
            scheduleWakeRecovery()
        }
    }

    private fun stopDedicatedWakeWordEngine() {
        wakeState(WakeState.STOPPED)
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

    private fun startCommandListening(releaseAttempts: Int = 0) {
        if (sherpaWakeWordEngine?.hasLiveWorker() == true) {
            if (sherpaWakeWordEngine?.hasStalled() == true || releaseAttempts >= 30) {
                commandFromWake = false
                wakeState(WakeState.FAILED)
                VoiceSessionDiagnostics.record(VoiceSessionDiagnostics.Stage.ERROR)
                onStatus("Command capture could not start because the previous wake worker has not released its microphone. No speech was submitted. Review voice diagnostics.")
                onCaptureFinished()
            } else {
                val generation = wakeGeneration
                mainHandler.postDelayed({ if (voiceActive && generation == wakeGeneration) startCommandListening(releaseAttempts + 1) }, 100L)
            }
            return
        }
        if (!isRecognitionAvailable()) {
            onStatus("Speech recognition is unavailable for command capture.")
            onCaptureFinished()
            if (commandFromWake) { commandFromWake = false; resumeHotwordEngineAfterCommand() }
            return
        }
        mode = RecognitionMode.COMMAND
        usedSystemFallback = false
        captureScreenScope = if (context.packageName == "com.assistant.core") screenAccess.voiceScope() else ""
        var hypotheses = emptyList<Pair<String, Float>>()
        commandCapture.listen(config.preferOfflineCommandRecognition, onPartial = {},
            onResult = { text ->
                observe(VoiceInteractionState.PROCESSING)
                val selected = hypotheses.ifEmpty { listOf(text to -1f) }
                onResults(Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList(selected.map { it.first }))
                    putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, selected.map { it.second }.toFloatArray())
                })
            }, onError = { error -> usedSystemFallback = true; onError(error) },
            onHypotheses = { hypotheses = it },
            onEngine = { onDeviceCapture = it == SpeechCaptureRouting.Engine.ON_DEVICE
                onStatus("Starting ${if (onDeviceCapture) "on-device" else "system"} command recognition; waiting for readiness.") },
            onPhase = { phase -> observe(phase); onCapturePhase(phase) })
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
        val candidates = SpeechRecognitionCandidates.read(matches, confidenceScores)
        val primary = candidates.firstOrNull()
        val command = primary?.transcript.orEmpty()
        if (primary == null) {
            onStatus("No command detected. Push-to-talk ended.")
            if (commandFromWake) { commandFromWake = false; resumeHotwordEngineAfterCommand() }
            onCaptureFinished()
        } else if (context.packageName == "com.assistant.core" &&
            !VoiceScreenApprovalScope.valid(command, captureScreenScope, screenAccess.voiceScope())) {
            onStatus("Screen review changed during voice capture; confirmation discarded.")
            speak("The screen review changed while you were speaking. Review the current target and confirm again; nothing was tapped.")
        } else {
            onCommandDetected(
                VoiceRecognitionResult(
                    transcript = command,
                    confidence = primary.confidence,
                    alternatives = candidates.map { it.transcript },
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

    private fun createRecognizer(preferOffline: Boolean): SpeechRecognizer {
        resetRecognition()
        val engine = requireNotNull(recognitionEngine(preferOffline)) { "No requested speech recognizer is available" }
        onDeviceCapture = engine == SpeechCaptureRouting.Engine.ON_DEVICE
        val recognizer = AndroidSpeechCapturePlatform.createRecognizer(context, engine)
        val token = captures.begin()
        captureToken = token
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { if (captures.valid(token)) this@VoiceAssistantService.onReadyForSpeech(params) }
            override fun onBeginningOfSpeech() { if (captures.valid(token)) this@VoiceAssistantService.onBeginningOfSpeech() }
            override fun onRmsChanged(value: Float) { if (captures.valid(token)) this@VoiceAssistantService.onRmsChanged(value) }
            override fun onBufferReceived(value: ByteArray?) { if (captures.valid(token)) this@VoiceAssistantService.onBufferReceived(value) }
            override fun onEndOfSpeech() { if (captures.valid(token)) this@VoiceAssistantService.onEndOfSpeech() }
            override fun onError(error: Int) { if (captures.finish(token)) this@VoiceAssistantService.onError(error) }
            override fun onResults(results: Bundle?) { if (captures.finish(token)) this@VoiceAssistantService.onResults(results) }
            override fun onPartialResults(results: Bundle?) { if (captures.valid(token)) this@VoiceAssistantService.onPartialResults(results) }
            override fun onEvent(type: Int, params: Bundle?) { if (captures.valid(token)) this@VoiceAssistantService.onEvent(type, params) }
        })
        speechRecognizer = recognizer
        return recognizer
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
        val recognizer = runCatching { createRecognizer(preferOffline) }.getOrElse {
            onStatus("Command capture could not start. Check microphone permission and your speech recognizer.")
            onCaptureFinished()
            if (commandFromWake) { commandFromWake = false; resumeHotwordEngineAfterCommand() }
            return
        }
        captureScreenScope = if (context.packageName == "com.assistant.core") screenAccess.voiceScope() else ""
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
        val timing = RuntimeSettings.read(context, "voice_settings")
        val window = VoiceCaptureWindow({ android.os.SystemClock.elapsedRealtime() },
            maximumMillis = timing.getLong("capture_max_millis", 180_000).coerceIn(60_000, 600_000))
        legacyCaptureWindow = window
        try { recognizer.startListening(intent) } catch (_: Exception) {
            resetRecognition(); onError(SpeechRecognizer.ERROR_CLIENT); return
        }
        val deadlineToken = captureToken
        val watchdog = object : Runnable {
            override fun run() {
                if (!captures.valid(deadlineToken)) return
                if (window.expired() && captures.finish(deadlineToken)) {
                    runCatching { speechRecognizer?.cancel() }
                    onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                } else mainHandler.postDelayed(this, 1000L)
            }
        }
        mainHandler.postDelayed(watchdog, 1000L)
        if (mode == RecognitionMode.FALLBACK_SPEECH_HOTWORD) {
            if (!fallbackStatusAnnounced) {
                onStatus("Speech fallback hotword mode active.")
                fallbackStatusAnnounced = true
            }
        } else {
            onStatus(if (onDeviceCapture) "Listening with Android on-device speech recognition."
                else "Listening with the system speech recognizer; offline availability depends on its installed models.")
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

}
