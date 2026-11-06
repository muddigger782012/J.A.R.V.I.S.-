package com.assistant.core.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.assistant.core.MainActivity
import com.assistant.core.R
import com.assistant.core.adapters.DhizukuAdapter
import com.assistant.core.adapters.ShizukuAdapter
import com.assistant.core.adapters.SpecialAccessAdapter
import com.assistant.core.adapters.StandardAdapter
import com.assistant.core.engine.ActionExecutor
import com.assistant.core.engine.ActionRegistry
import com.assistant.core.engine.AssistantEngine
import com.assistant.core.engine.CapabilityBroker
import com.assistant.core.engine.CapabilityDetector
import com.assistant.core.engine.IntentClassifier
import com.assistant.core.engine.PolicyGate
import com.assistant.core.models.ActionResult
import com.assistant.core.models.CapabilityState
import com.assistant.core.storage.ActionRepository
import com.assistant.core.storage.AuditRepository
import com.assistant.core.storage.Database
import com.assistant.core.storage.ProjectRepository

class VoiceForegroundService : Service() {

    private lateinit var voicePreferences: VoicePreferences
    private lateinit var voiceCommandParser: VoiceCommandParser
    private lateinit var voiceService: VoiceAssistantService
    private lateinit var assistantEngine: AssistantEngine
    private lateinit var actionRegistry: ActionRegistry
    private lateinit var hybridAssistantService: HybridAssistantService
    private lateinit var systemService: SystemService
    private lateinit var capabilityState: CapabilityState
    private lateinit var currentConfig: VoiceConfig
    private lateinit var voiceSession: VoiceSessionController

    private var pendingClarification: String? = null
    private var practiceToken: String? = null
    private var resumeAfterPractice = false
    private val practiceTimeout = Runnable { releasePracticeMicrophone(practiceToken.orEmpty()) }
    private var pipelineStarted = false
    private var commandSessionActive = false
    private var responseGeneration = 0L
    private var spokenScreenContext: String? = null
    private var followUpScreen = false
    private val screenAccess by lazy { ScreenAccessClient(this) }
    private val screenChanges = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val scope = spokenScreenContext ?: return
            if (screenAccess.taskState().contextKey == scope) return
            spokenScreenContext = null; followUpScreen = false; responseGeneration++
            if (::hybridAssistantService.isInitialized) hybridAssistantService.cancelActiveResponse()
            if (::voiceService.isInitialized) {
                voiceService.stopSpeaking()
                if (!liveChatActive && pipelineStarted) { voiceService.stopListening(); voiceService.startHotwordLoop() }
            }
        }
    }
    private var appliedVoiceConfig: VoiceConfig? = null
    private var liveChatActive = false
    private var resumeWakeAfterLive = false
    private var liveConversation = ""
    private var liveRecordSession = ""
    private val dialogueOverlay by lazy { LiveChatDialogueOverlay(this) { finishLiveChat("Live chat ended.") } }
    private val responseOptionsReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { dialogueOverlay.refresh(liveChatActive) }
    }
    private val liveSession = LiveChatSession()
    private val companionOptionsReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!liveChatActive) return
            val saved = LiveChatCompanionSettings.load(this@VoiceForegroundService)
            liveSession.configurePersistentListening(
                packageName == "com.assistant.core" && saved.keepListeningUntilStopped
            )
            if (!saved.contextEnabled) ambientContext.clear()
        }
    }
    private val ambientParticipation = AmbientParticipationPolicy()
    private val ambientContext = AmbientContextWindow()
    private val liveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val liveCapture by lazy { LiveSpeechCapture(this) }
    private var liveMessage = ""
    private var livePhase = VoiceInteractionState.IDLE
    private var voicePhase = VoiceInteractionState.IDLE
    private var liveFailures = 0
    private var liveSilenceTurns = 0
    private var liveEngineOnDevice = false
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!liveChatActive) return
            LiveChatStore.state(this@VoiceForegroundService, true, livePhase, liveMessage)
            if (::voiceService.isInitialized) VoiceRuntimeObservation.write(this@VoiceForegroundService,
                VoiceRuntimeObservation.Owner.FOREGROUND, voiceService.wakeState, livePhase)
            dialogueOverlay.refresh(true)
            liveHandler.postDelayed(this, 30000L)
        }
    }

    private fun startVoiceForeground(message: String) {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(message),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
    }

    override fun onCreate() {
        super.onCreate()
        // Keep Service.onCreate lightweight. A failure in Sherpa/assistant
        // construction here occurs before onStartCommand and bypasses its
        // crash containment entirely.
        voicePreferences = VoicePreferences(this)
        ContextCompat.registerReceiver(this, responseOptionsReceiver, android.content.IntentFilter(VoiceResponsePreferences.ACTION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, companionOptionsReceiver, android.content.IntentFilter(LiveChatCompanionSettings.ACTION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, screenChanges, android.content.IntentFilter(ScreenTaskNotifications.CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        createNotificationChannel()
        PersonalReminderRuntime.restoreAsync(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PUSH_TO_TALK -> {
                if (liveChatActive) {
                    // The service, not the Activity's potentially stale label,
                    // determines what a mic tap does. Finish an active utterance;
                    // otherwise interrupt pending TTS/replies and rearm capture.
                    // A recognizer awaiting its final transcript is still active but a
                    // second finish() would be ignored. Treat that tap as recovery.
                    when (liveSession.microphoneTapped(liveCapture.isActive && !liveCapture.isFinishing)) {
                        LiveChatSession.MicrophoneTap.FINISH_CAPTURE -> {
                            liveCapture.finish()
                            liveStatus(VoiceInteractionState.TRANSCRIBING, "Finishing voice input…")
                        }
                        LiveChatSession.MicrophoneTap.RESTART_LISTENING -> {
                            hybridAssistantService.cancelActiveResponse()
                            liveCapture.stop()
                            liveFailures = 0
                            if (::voiceService.isInitialized) voiceService.stopSpeaking()
                            liveHandler.removeCallbacksAndMessages(null)
                            liveStatus(VoiceInteractionState.IDLE, "Microphone requested; listening again…")
                            liveHandler.post(heartbeat)
                            nextLiveTurn(150L)
                        }
                        LiveChatSession.MicrophoneTap.IGNORE -> Unit
                    }
                    return START_NOT_STICKY
                }
                if (practiceToken != null) return START_NOT_STICKY
                startVoiceForeground("Starting command capture…")
                try {
                    if (!::voiceService.isInitialized) { initializeEngine(); initializeVoice() }
                    hybridAssistantService.interruptActiveResponse()
                    responseGeneration++
                    spokenScreenContext = null; followUpScreen = false
                    currentConfig = voicePreferences.load()
                    voiceService.updateConfig(currentConfig)
                    val resumeWake = intent.getBooleanExtra("resume_wake", false)
                    pipelineStarted = pipelineStarted || resumeWake
                    if (resumeWake) voicePreferences.setForegroundServiceRunning(true)
                    commandSessionActive = true
                    voiceSession.beginCapture(VoiceSessionController.Trigger.PUSH_TO_TALK)
                    voiceService.startPushToTalk(returnToWake = pipelineStarted)
                } catch (_: Exception) {
                    commandSessionActive = false
                    if (::voiceSession.isInitialized) voiceSession.stop()
                    publishEvent("Command capture could not initialize. No speech was submitted.")
                    if (!pipelineStarted) stopSelf()
                }
                // A manual mic interaction must not revoke the previously enabled
                // screen-off standby restart contract.
                return if (WakeRestartPolicy.shouldRemainSticky(pipelineStarted)) START_STICKY else START_NOT_STICKY
            }
            ACTION_FINISH_CAPTURE -> {
                if (liveChatActive) liveCapture.finish() else if (::voiceService.isInitialized) voiceService.finishCapture()
                return if (WakeRestartPolicy.shouldRemainSticky(pipelineStarted)) START_STICKY else START_NOT_STICKY
            }
            ACTION_CANCEL_CAPTURE -> {
                if (liveChatActive) {
                    hybridAssistantService.cancelActiveResponse()
                    liveCapture.stop(); voiceService.stopSpeaking()
                    liveHandler.removeCallbacksAndMessages(null)
                    if (liveSession.interrupt()) {
                        liveStatus(VoiceInteractionState.IDLE, "Turn cancelled. Live chat remains active; listening again…")
                        liveHandler.post(heartbeat); nextLiveTurn(300L)
                    }
                    return START_NOT_STICKY
                }
                val resume = pipelineStarted
                if (::voiceService.isInitialized) stopVoicePipeline()
                if (resume) startVoicePipeline() else stopSelf()
                return if (WakeRestartPolicy.shouldRemainSticky(pipelineStarted)) START_STICKY else START_NOT_STICKY
            }
            ACTION_PRACTICE_PAUSE -> {
                val token = intent.getStringExtra("token").orEmpty()
                if (token.isBlank()) return START_NOT_STICKY
                if (liveChatActive || practiceToken != null) {
                    VoicePracticeMicrophone.acknowledge(this, token, false)
                    return START_NOT_STICKY
                }
                practiceToken = token
                resumeAfterPractice = pipelineStarted
                if (::voiceService.isInitialized) { stopVoicePipeline(); voiceService.stopSpeaking() }
                VoicePracticeMicrophone.acknowledge(this, token, true)
                liveHandler.postDelayed(practiceTimeout, 30000L)
                return START_NOT_STICKY
            }
            ACTION_PRACTICE_RELEASE -> {
                releasePracticeMicrophone(intent.getStringExtra("token").orEmpty())
                return START_NOT_STICKY
            }
            ACTION_LIVE_START -> {
                if (practiceToken != null) return START_NOT_STICKY
                startVoiceForeground("Starting live chat…")
                if (!liveChatActive) {
                    try {
                        resumeWakeAfterLive = pipelineStarted || intent.getBooleanExtra("resume_wake", false)
                        liveConversation = intent.getStringExtra("conversation").orEmpty()
                        liveRecordSession = java.util.UUID.randomUUID().toString()
                        LiveChatStore.lastReply(this, org.json.JSONObject())
                        if (!::voiceService.isInitialized) { initializeEngine(); initializeVoice() }
                        voiceService.stopListening()
                        voiceService.stopSpeaking()
                        pipelineStarted = false
                        voicePreferences.setForegroundServiceRunning(false)
                        currentConfig = voicePreferences.load()
                        voiceService.updateConfig(currentConfig)
                        liveChatActive = true
                        liveSession.configureIdleTimeout(RuntimeSettings.read(this, "voice_settings")
                            .getLong("live_idle_millis", 300_000))
                        // Only explicitly started Personal Live Chat can bypass inactivity
                        // expiry. The visible foreground microphone and Stop action remain.
                        liveSession.configurePersistentListening(packageName == "com.assistant.core" &&
                            LiveChatCompanionSettings.load(this).keepListeningUntilStopped)
                        liveSession.start()
                        ambientParticipation.reset()
                        ambientContext.clear()
                        liveSilenceTurns = 0
                        liveEngineOnDevice = false
                        liveStatus(VoiceInteractionState.LISTENING, "Starting live chat…")
                        liveHandler.post(heartbeat)
                        nextLiveTurn(800L)
                    } catch (error: Throwable) {
                        liveChatActive = true
                        finishLiveChat("Live chat couldn’t start: ${error.javaClass.simpleName}")
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_LIVE_STOP -> {
                if (liveChatActive) finishLiveChat("Live chat ended.")
                else if (!pipelineStarted) stopSelf()
                return if (WakeRestartPolicy.shouldRemainSticky(pipelineStarted)) START_STICKY else START_NOT_STICKY
            }
            ACTION_STOP -> {
                practiceToken = null
                resumeAfterPractice = false
                liveHandler.removeCallbacks(practiceTimeout)
                if (liveChatActive) { resumeWakeAfterLive = false; finishLiveChat("Live chat ended.") }
                if (::voiceService.isInitialized) stopVoicePipeline()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (liveChatActive || practiceToken != null) return START_NOT_STICKY
                // A sticky process restart has a null Intent. Resume only a wake
                // service previously enabled by the user, never invent a new session.
                if (intent == null && !voicePreferences.isForegroundServiceRunning()) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                startVoiceForeground("Voice service is active")
                persistStage("entered-start-command")
                publishEvent("Foreground service entered start command.")
                // Android may deliver repeated startService intents (activity resume,
                // settings reload, sticky restart). Do not tear down and recreate
                // the active Sherpa microphone pipeline for every duplicate start.
                // This Service instance owns the live pipeline. A persisted
                // "running" flag can survive process death and must never be used
                // as proof that Sherpa is actually armed in this new instance.
                if (!pipelineStarted) {
                    try {
                        publishEvent("Initializing foreground wake pipeline...")
                        persistStage("before-engine")
                        initializeEngine()
                        persistStage("after-engine")
                        initializeVoice()
                        persistStage("after-voice")
                        startVoicePipeline(announce = intent?.getBooleanExtra("announce", false) == true)
                        persistStage("pipeline-started")
                    } catch (t: Throwable) {
                        pipelineStarted = false
                        persistStage("foreground-pipeline-failed")
                        voicePreferences.setForegroundServiceRunning(false)
                        publishEvent("Foreground wake pipeline initialization failed. Review microphone permission, wake models and voice diagnostics.")
                    }
                } else {
                    val updatedConfig = voicePreferences.load()
                    if (updatedConfig != appliedVoiceConfig) {
                        currentConfig = updatedConfig
                        appliedVoiceConfig = updatedConfig
                        voiceService.updateConfig(updatedConfig)
                        publishEvent("Voice settings applied; wake sensitivity updated.")
                    } else {
                        if (voiceService.wakeState in setOf(VoiceAssistantService.WakeState.FAILED, VoiceAssistantService.WakeState.STOPPED) &&
                            !commandSessionActive && !voiceService.isCapturing() && voicePhase !in setOf(VoiceInteractionState.PROCESSING, VoiceInteractionState.SPEAKING)) {
                            voiceService.startHotwordLoop()
                            publishEvent("Wake standby recovery requested; readiness awaits the actual engine callback.")
                        } else publishEvent("Foreground service already active. Current voice state: ${voicePhase.userLabel()}.")
                    }
                }
                // The microphone foreground service must be recreatable if Android
                // reclaims :wake while the screen is off. Explicit STOP remains non-sticky.
                return if (pipelineStarted) START_STICKY else START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        // System teardown is not the owner's STOP command. Preserve the
        // opted-in wake request so START_STICKY can restore standby when
        // Android recreates this service after screen-off process reclamation.
        val restoreRequestedWake = pipelineStarted && voicePreferences.isForegroundServiceRunning()
        runCatching { unregisterReceiver(screenChanges) }
        runCatching { unregisterReceiver(responseOptionsReceiver) }
        runCatching { unregisterReceiver(companionOptionsReceiver) }
        dialogueOverlay.hide()
        liveHandler.removeCallbacksAndMessages(null)
        liveCapture.stop()
        if (liveChatActive) {
            liveChatActive = false
            liveSession.stop()
            LiveChatStore.state(this, false, VoiceInteractionState.IDLE, "Live chat stopped.")
            sendBroadcast(Intent(ACTION_EVENT).setPackage(packageName).putExtra(EXTRA_LIVE_CHAT, true))
        }
        if (::voiceService.isInitialized) {
            stopVoicePipeline(clearDesiredWake = !restoreRequestedWake)
            voiceService.shutdown()
        } else {
            pipelineStarted = false
            if (!restoreRequestedWake) voicePreferences.setForegroundServiceRunning(false)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun liveStatus(phase: VoiceInteractionState, message: String) {
        livePhase = phase
        liveMessage = message
        if (::voiceService.isInitialized) VoiceRuntimeObservation.write(this, VoiceRuntimeObservation.Owner.FOREGROUND, voiceService.wakeState, phase)
        LiveChatStore.state(this, liveChatActive, phase, message)
        dialogueOverlay.refresh(liveChatActive)
        updateNotification(message)
        sendBroadcast(Intent(ACTION_EVENT).setPackage(packageName).putExtra(EXTRA_LIVE_CHAT, true))
    }

    private fun liveLine(text: String) {
        val entry = LiveChatStore.append(this, liveConversation, text, liveRecordSession)
        if (text.startsWith("J.A.R.V.I.S.: ")) {
            LiveChatStore.lastReply(this, entry)
            dialogueOverlay.refresh(liveChatActive)
        }
        sendBroadcast(Intent(ACTION_EVENT).setPackage(packageName).putExtra(EXTRA_LIVE_CHAT, true))
    }

    private fun speakLiveReply(text: String) {
        val audible = VoiceResponsePreferences.load(this).liveSpeech
        liveStatus(if (audible) VoiceInteractionState.SPEAKING else VoiceInteractionState.IDLE,
            if (audible) "Speaking…" else "Reply ready. Listening again…")
        val turn = liveSession.token
        if (!audible) {
            // Text-only Live Chat must not invoke TTS or wait for a speech
            // completion callback that a muted engine may never deliver.
            if (liveSession.silentResponseFinished(turn)) nextLiveTurn(250L)
            return
        }
        voiceService.speak(text, SpeechChannel.LIVE_CHAT, after = {
            // TTS completion may arrive on an engine callback thread. Serialize
            // session transitions with the main-thread capture and watchdog.
            liveHandler.post {
                if (liveChatActive && liveSession.speechFinished(turn)) nextLiveTurn(250L)
            }
        })
        // An absolute-length timeout can cut off a healthy long spoken reply.
        // Check *inactivity* instead: PCM chunks and Android TTS utterance
        // starts refresh this clock, while a hung provider still recovers.
        val monitor = object : Runnable {
            override fun run() {
                if (!liveChatActive || liveSession.token != turn ||
                    liveSession.phase != LiveChatSession.Phase.SPEAKING) return
                if (SpeechPlaybackStallPolicy.stalled(
                        android.os.SystemClock.elapsedRealtime(), voiceService.lastSpeechProgressElapsed())) {
                    if (liveSession.speechFinished(turn)) {
                        liveStatus(VoiceInteractionState.IDLE,
                            "Speech output stopped progressing. Tap the microphone if it does not resume.")
                        voiceService.stopSpeaking()
                        nextLiveTurn(650L)
                    }
                } else liveHandler.postDelayed(this, 15_000L)
            }
        }
        liveHandler.postDelayed(monitor, 30_000L)
    }

    private fun nextLiveTurn(delay: Long) {
        val token = liveSession.token
        liveHandler.postDelayed({
            if (!liveChatActive || liveSession.token != token) return@postDelayed
            val turn = liveSession.listen() ?: run {
                if (liveSession.expired()) finishLiveChat("Live chat reached your configured inactivity limit.")
                return@postDelayed
            }
            liveStatus(VoiceInteractionState.IDLE, "Starting speech capture; waiting for the recognizer.")
            // LiveSpeechCapture owns the activity-aware recognizer watchdog:
            // it expires idle or stalled providers, but permits active speech
            // (including long dictation) until the configured maximum.
            // Do not install a second absolute 30-second timer here: it would
            // cancel an actively speaking user before results can be delivered.
            val captureScope = if (packageName == "com.assistant.core") screenAccess.voiceScope() else ""
            val developmentScope = DevelopmentInstallConsent.voiceScope()
            liveCapture.listen(currentConfig.preferOfflineCommandRecognition,
                onPartial = { /* Partial transcripts stay transient; only complete turns enter chat. */ },
                onResult = { text ->
                    if (liveChatActive && liveSession.transcript(turn)) {
                        liveFailures = 0
                        liveSilenceTurns = 0
                        val companionSettings = LiveChatCompanionSettings.load(this)
                        if (!companionSettings.contextEnabled) ambientContext.clear()
                        val participation = ambientParticipation.decide(text, companionSettings)
                        // Ambient observations must never reach command execution or the AI router.
                        // STOP remains a reliable explicit exit even in Quiet mode.
                        if (participation == AmbientParticipationPolicy.Decision.OBSERVE &&
                            LiveChatCommands.parse(text) != LiveChatCommands.Control.STOP) {
                            // Unaddressed room speech remains volatile and is never sent
                            // through the AI, action router, or persistent Live Chat history.
                            ambientContext.observe(text, companionSettings.contextEnabled)
                            if (liveSession.recover(turn)) {
                                liveStatus(VoiceInteractionState.IDLE, "Listening; no response needed.")
                                nextLiveTurn(250L)
                            }
                        } else {
                        liveLine("You: $text")
                        if (packageName == "com.assistant.core" && !VoiceDevelopmentApprovalScope.valid(text, developmentScope, DevelopmentInstallConsent.voiceScope())) {
                            liveSession.response(turn)
                            val message = "The APK review changed while you were speaking. Review the latest APK and repeat the specific approval; no installation approval was sent."
                            liveLine("J.A.R.V.I.S.: $message"); speakLiveReply(message)
                        } else if (packageName == "com.assistant.core" && !VoiceScreenApprovalScope.valid(text, captureScope, screenAccess.voiceScope())) {
                            liveSession.response(turn)
                            val message = "The screen review changed during voice capture. Review the current target and confirm again; nothing was tapped."
                            liveLine("J.A.R.V.I.S.: $message"); speakLiveReply(message)
                        } else if (LiveChatCommands.parse(text) == LiveChatCommands.Control.STOP) {
                            finishLiveChat("Live chat ended.")
                        } else if (LiveChatCommands.parse(text) == LiveChatCommands.Control.START) {
                            liveSession.response(turn)
                            liveLine("J.A.R.V.I.S.: Live chat is already active.")
                            speakLiveReply("Live chat is already active.")
                        } else if (ambientContext.recall(text, companionSettings.contextEnabled)?.let { remembered ->
                            if (liveSession.response(turn)) {
                                liveLine("J.A.R.V.I.S.: $remembered")
                                speakLiveReply(remembered)
                            }
                            true
                        } == true) {
                            // Local ambient recall never executes a tool, calls cloud AI,
                            // or attributes speech to a specific person.
                        } else {
                            liveStatus(VoiceInteractionState.PROCESSING, "Thinking…")
                            // Send the original utterance through the shared assistant router.
                            // It already supplies bounded conversation memory to the AI;
                            // prepending a transcript here can hide actionable commands
                            // (Developer tasks, reminders, confirmations) from parsers.
                            val replyStartedAt = android.os.SystemClock.elapsedRealtime()
                            askAssistant(text) { reply ->
                                liveHandler.post {
                                    if (!liveChatActive || liveSession.token != turn ||
                                        liveSession.phase != LiveChatSession.Phase.THINKING) return@post
                                    // Check scope before transitioning to SPEAKING: recover() only
                                    // accepts LISTENING or THINKING, not SPEAKING.
                                    if (reply.screenContextKey != null && screenAccess.taskState().contextKey != reply.screenContextKey) {
                                        if (liveSession.recover(turn)) {
                                            liveStatus(VoiceInteractionState.IDLE, "Screen context changed; listening again…")
                                            nextLiveTurn(650L)
                                        }
                                        return@post
                                    }
                                    if (liveSession.response(turn)) {
                                        publishEvent("Voice diagnostic: assistant turnaround ${android.os.SystemClock.elapsedRealtime() - replyStartedAt}ms.")
                                        liveLine("J.A.R.V.I.S.: ${reply.text}")
                                        speakLiveReply(reply.text)
                                    }
                                }
                            }
                            liveHandler.postDelayed({
                                if (liveChatActive) when (liveSession.responseTimedOut(turn)) {
                                    LiveChatSession.ReplyRecovery.RETRY -> {
                                        hybridAssistantService.cancelActiveResponse()
                                        liveStatus(VoiceInteractionState.IDLE, "The reply timed out. Live chat is still active; ask again.")
                                        nextLiveTurn(1000L)
                                    }
                                    LiveChatSession.ReplyRecovery.EXPIRED -> finishLiveChat("Live chat reached your configured inactivity limit.")
                                    LiveChatSession.ReplyRecovery.STALE -> Unit
                                }
                            }, 60000L)
                        }
                        } // End response-worthy ambient decision.
                    }
                },
                onError = { error -> liveCaptureError(turn, error) },
                onEngine = { engine ->
                    if (liveChatActive && liveSession.token == turn && liveSession.phase == LiveChatSession.Phase.LISTENING) {
                        liveEngineOnDevice = engine == SpeechCaptureRouting.Engine.ON_DEVICE
                        val source = if (engine == SpeechCaptureRouting.Engine.ON_DEVICE) "Listening on device."
                            else "Listening with the system speech recognizer; it may use a network connection."
                        liveStatus(VoiceInteractionState.IDLE, "$source Waiting for microphone readiness.")
                    }
                }, onPhase = { phase ->
                    if (liveChatActive && liveSession.token == turn && liveSession.phase == LiveChatSession.Phase.LISTENING)
                        liveStatus(phase, phase.userLabel())
                }, minimumSpeechPauseMillis = ConversationCaptureTiming.LIVE_MINIMUM_PAUSE_MILLIS,
                   minimumCaptureMaximumMillis = ConversationCaptureTiming.LIVE_MINIMUM_MAX_CAPTURE_MILLIS,
                // Normal silence must not start a second system recognizer,
                // because many Android providers chime on every startup.
                fallbackOnSilence = false,
                // Some providers emit useful partial recognition but no final
                // result after a thought pause. Do not discard those words.
                recoverPartialOnFinalFailure = true
            )
        }, delay)
    }

    private fun liveCaptureError(turn: Long, error: Int) {
        if (!liveChatActive || liveSession.token != turn || liveSession.phase != LiveChatSession.Phase.LISTENING) return
        if (error == android.speech.SpeechRecognizer.ERROR_NO_MATCH || error == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            if (liveSession.silence(turn)) {
                val silentTurns = ++liveSilenceTurns
                val persistentSession = packageName == "com.assistant.core" &&
                    LiveChatCompanionSettings.load(this).keepListeningUntilStopped
                when (LiveChatChimePolicy.afterSilence(liveEngineOnDevice, silentTurns, persistentSession)) {
                    LiveChatChimePolicy.Action.PAUSE ->
                        liveStatus(VoiceInteractionState.IDLE,
                            "System microphone paused after repeated silence to avoid recurring notification tones. Tap the microphone to resume.")
                    LiveChatChimePolicy.Action.RETRY -> {
                        if (persistentSession && !liveEngineOnDevice) {
                            liveStatus(VoiceInteractionState.IDLE,
                                "Live Chat is active. System recognition is resting between listening attempts; it may chime on restart.")
                        } else liveStatus(VoiceInteractionState.IDLE, "Still here. Listening for your next thought…")
                        nextLiveTurn(LiveChatChimePolicy.silenceRetryDelayMillis(liveEngineOnDevice, silentTurns, persistentSession))
                    }
                }
            } else finishLiveChat("Live chat reached your configured inactivity limit.")
        } else {
            val message = when (error) {
                android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow microphone permission to use live chat."
                android.speech.SpeechRecognizer.ERROR_NETWORK, android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition lost its connection."
                android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The microphone is busy."
                else -> "Speech recognition stopped ($error)."
            }
            if (error != android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS && liveSession.recover(turn)) {
                liveFailures++
                if (LiveChatChimePolicy.afterProviderError(liveFailures) == LiveChatChimePolicy.Action.RETRY) {
                    liveStatus(VoiceInteractionState.IDLE, "$message Trying the microphone once more.")
                    nextLiveTurn(1500L)
                } else {
                    liveStatus(VoiceInteractionState.IDLE,
                        "$message Live chat is paused to avoid repeated microphone chimes. Tap the microphone to resume.")
                }
            } else finishLiveChat(message)
        }
    }

    private fun finishLiveChat(message: String) {
        if (::hybridAssistantService.isInitialized) hybridAssistantService.cancelActiveResponse()
        responseGeneration++; spokenScreenContext = null; followUpScreen = false
        dialogueOverlay.hide()
        liveChatActive = false
        liveSession.stop()
        ambientContext.clear()
        liveHandler.removeCallbacksAndMessages(null)
        liveCapture.stop()
        if (::voiceService.isInitialized) voiceService.stopSpeaking()
        liveStatus(VoiceInteractionState.IDLE, message)
        if (resumeWakeAfterLive && ::voiceService.isInitialized) {
            resumeWakeAfterLive = false
            startVoicePipeline()
            // A live-chat start was non-sticky while conversational capture
            // owned the microphone. Restore sticky wake mode after the chat
            // ends, including when it ends by a spoken command.
            runCatching { startService(Intent(this, VoiceForegroundService::class.java).setAction(ACTION_START)) }
                .onFailure { publishEvent("Wake restart contract could not be refreshed: ${it.javaClass.simpleName}") }
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun initializeEngine() {
        val database = Database(this)
        val projectRepository = ProjectRepository(database)
        val actionRepository = ActionRepository(database)
        val auditRepository = AuditRepository(database)
        val fileService = FileService(this)
        val codingService = CodingService(this, fileService)
        systemService = SystemService(this)
        val auditService = AuditService(auditRepository)
        val standardAdapter = StandardAdapter(codingService, fileService, systemService, projectRepository)
        val shizukuAdapter = ShizukuAdapter(this)
        val dhizukuAdapter = DhizukuAdapter(this, DhizukuService())
        val specialAccessAdapter = SpecialAccessAdapter()
        capabilityState = CapabilityDetector().detect(this)

        actionRegistry = ActionRegistry()
        voiceCommandParser = VoiceCommandParser(actionRegistry)
        assistantEngine = AssistantEngine(
            intentClassifier = IntentClassifier(),
            actionRegistry = actionRegistry,
            policyGate = PolicyGate(),
            actionExecutor = ActionExecutor(
                CapabilityBroker(
                    standardAdapter = standardAdapter,
                    shizukuAdapter = shizukuAdapter,
                    dhizukuAdapter = dhizukuAdapter,
                    specialAccessAdapter = specialAccessAdapter
                )
            ),
            actionRepository = actionRepository,
            auditService = auditService,
            capabilityProvider = { capabilityState }
        )
        hybridAssistantService = HybridAssistantService(
            assistantEngine = assistantEngine,
            actionRegistry = actionRegistry,
            systemService = systemService,
            capabilityProvider = { capabilityState }
        )
    }

    private fun initializeVoice() {
        voiceSession = VoiceSessionController(
            onStateChanged = { state -> voicePhase = state; publishEvent("Voice state: ${state.userLabel()}") },
            onTranscript = { transcript -> processTranscript(transcript) },
            onError = { failure -> publishEvent("Voice error: ${failure.message}") }
        )
        voiceService = VoiceAssistantService(
            context = this,
            onStatus = { status -> publishEvent(status) },
            onWakeState = { state -> voiceSession.wakeState(state == VoiceAssistantService.WakeState.LISTENING) },
            onCapturePhase = { phase -> if (phase == VoiceInteractionState.TRANSCRIBING) voiceSession.transcribing() },
            onHotwordDetected = {
                publishEvent("Voice diagnostic: wake word detected; requesting command capture.")
                voiceSession.beginCapture(VoiceSessionController.Trigger.WAKE_WORD)
            },
            onCommandDetected = { recognition ->
                val heard = recognition.transcript.trim()
                if (heard.isEmpty()) {
                    publishEvent("Voice diagnostic: recognition completed with no transcript.")
                } else {
                    publishEvent("Voice diagnostic: transcript received (${heard.length} characters); routing to assistant.")
                    voiceSession.transcriptReady(heard)
                }
            },
            onCaptureFinished = { if (!liveChatActive) {
                voiceSession.captureFinished(keepArmed = pipelineStarted)
                commandSessionActive = false
                if (!pipelineStarted) stopSelf()
            } },
            onSpeechStarted = {
                publishEvent("Voice diagnostic: spoken response playback started.")
                voiceSession.speaking()
            },
            onSpeechFinished = {
                publishEvent("Voice diagnostic: spoken response playback finished.")
                // Generic TTS completions have no Live Chat turn token and may
                // belong to speech cancelled by a newer turn. Only speakLiveReply's
                // turn-scoped callback (or its timeout) can rearm live capture.
                if (!liveChatActive) {
                    val state = if (packageName == "com.assistant.core") screenAccess.taskState() else ScreenAccessClient.TaskState()
                    val follow = followUpScreen && state.awaitingInput && state.contextKey == spokenScreenContext
                    followUpScreen = false
                    voiceSession.responseFinished(keepArmed = true)
                    if (follow && pipelineStarted) {
                        voiceSession.beginCapture(VoiceSessionController.Trigger.BACKGROUND)
                        voiceService.startFollowUpCapture()
                    } else voiceService.rearmAfterResponse()
                    commandSessionActive = false
                    if (!pipelineStarted) stopSelf()
                }
            }
        )
    }

    private fun releasePracticeMicrophone(token: String) {
        if (practiceToken != token) {
            if (practiceToken == null && !liveChatActive && !pipelineStarted) stopSelf()
            return
        }
        practiceToken = null
        liveHandler.removeCallbacks(practiceTimeout)
        val resume = resumeAfterPractice
        resumeAfterPractice = false
        if (resume && ::voiceService.isInitialized) startVoicePipeline()
        else if (!liveChatActive && !pipelineStarted) stopSelf()
    }

    private fun startVoicePipeline(announce: Boolean = false) {
        currentConfig = voicePreferences.load()
        appliedVoiceConfig = currentConfig
        voiceService.updateConfig(currentConfig)
        if (announce) voiceService.startWithIntroduction() else voiceService.startHotwordLoop()
        pipelineStarted = true
        voicePreferences.setForegroundServiceRunning(true)
        publishEvent("Foreground voice service started.")
    }

    private fun stopVoicePipeline(clearDesiredWake: Boolean = true) {
        responseGeneration++; spokenScreenContext = null; followUpScreen = false
        if (::hybridAssistantService.isInitialized) hybridAssistantService.cancelActiveResponse()
        if (packageName == "com.assistant.core") screenAccess.discard()
        voiceService.stopSpeaking()
        voiceService.stopListening()
        pipelineStarted = false
        voiceSession.stop()
        pendingClarification = null
        if (clearDesiredWake) voicePreferences.setForegroundServiceRunning(false)
        publishEvent("Foreground voice service stopped.")
    }

    // Keep voice commands intact. HybridAssistantService supplies the bounded
    // conversation history to online AI without rewriting the user's utterance.
    private fun askAssistant(text: String, callback: (HybridAssistantReply) -> Unit) {
        hybridAssistantService.handleUserInputAsync(text) { if (!it.cancelled) callback(it) }
    }

    private fun processTranscript(transcript: String) {
        val generation = ++responseGeneration
        spokenScreenContext = null; followUpScreen = false
        voiceService.stopSpeaking()
        currentConfig = voicePreferences.load()
        publishEvent("You: $transcript")
        when (LiveChatCommands.parse(transcript)) {
            LiveChatCommands.Control.START -> {
                val conversation = LiveChatStore.currentConversation(this).ifBlank {
                    getSharedPreferences("jarvis_unified_chat", MODE_PRIVATE).getString("active_session", "session_" + System.currentTimeMillis()).orEmpty()
                }
                LiveChatStore.append(this, conversation, "You: $transcript")
                LiveChatStore.append(this, conversation, "J.A.R.V.I.S.: Starting live chat.")
                onStartCommand(Intent(this, VoiceForegroundService::class.java).setAction(ACTION_LIVE_START)
                    .putExtra("conversation", conversation).putExtra("resume_wake", true), 0, 0)
                return
            }
            LiveChatCommands.Control.STOP -> {
                speakResponse("Live chat is already stopped.")
                return
            }
            else -> Unit
        }

        val awaiting = pendingClarification
        if (awaiting != null) {
            when {
                looksLikeYes(transcript) -> {
                    pendingClarification = null
                    processCommand(awaiting)
                }
                looksLikeNo(transcript) -> {
                    pendingClarification = null
                    speakResponse("Okay. Please repeat your command.")
                }
                else -> speakResponse("Please answer yes or no.")
            }
            return
        }

        // Voice 2.0 deliberately sends recognized speech through the same
        // central assistant pipeline as typed input. Legacy parser routing is
        // retained below only until the remaining migration is complete.
        publishEvent("Voice diagnostic: live transcript submitted to assistant.")
        askAssistant(transcript) { reply ->
            liveHandler.post {
                if (generation != responseGeneration || (!pipelineStarted && !commandSessionActive) || practiceToken != null || liveChatActive) return@post
                val scope = reply.screenContextKey
                if (scope != null && screenAccess.taskState().contextKey != scope) {
                    voiceSession.responseFinished(keepArmed = true); voiceService.rearmAfterResponse(); return@post
                }
                spokenScreenContext = scope
                followUpScreen = scope != null && scope.isNotBlank()
                publishEvent("Voice diagnostic: live assistant response received (${reply.text.length} characters).")
                publishEvent("J.A.R.V.I.S.: ${reply.text}")
                speakResponse(reply.text)
            }
        }
    }

    private fun speakResponse(text: String) {
        // A reply started before practice may finish asynchronously. Keep it
        // visible in events without playing speech into calibration audio.
        if (practiceToken != null) return
        voiceService.speak(text)
    }

    private fun processRecognition(recognition: VoiceRecognitionResult) {
        currentConfig = voicePreferences.load()
        val transcript = recognition.transcript.trim()
        if (transcript.isEmpty()) {
            publishEvent("Voice recognition returned an empty transcript; waiting for another command.")
            return
        }
        publishEvent(
            "Voice command (${(recognition.confidence * 100f).toInt()}%): $transcript"
        )

        val awaiting = pendingClarification
        if (awaiting != null) {
            when {
                looksLikeYes(transcript) -> {
                    pendingClarification = null
                    processCommand(awaiting)
                }
                looksLikeNo(transcript) -> {
                    pendingClarification = null
                    voiceService.speak("Okay. Please repeat your command.")
                }
                else -> {
                    voiceService.speak("Please answer yes or no.")
                }
            }
            return
        }

        if (recognition.confidence < currentConfig.commandConfidenceThreshold) {
            pendingClarification = transcript
            voiceService.speak("I heard $transcript. Is that correct? Say yes or no.")
            publishEvent("Low confidence detected. Awaiting clarification.")
            return
        }

        processCommand(transcript)
    }

    private fun processCommand(command: String) {
        val parsed = voiceCommandParser.parse(command, currentConfig)
        parsed.responseHint?.let { publishEvent(it) }

        when (parsed.localCommand) {
            LocalVoiceCommand.STOP_VOICE -> {
                voiceService.speak("Stopping background voice mode.")
                stopSelf()
            }
            LocalVoiceCommand.START_VOICE -> {
                voiceService.speak("Background voice mode is already active.")
            }
            LocalVoiceCommand.OPEN_SETTINGS -> {
                voiceService.speak("Open the app to change voice settings.")
                publishEvent("Voice settings requested; open app to configure.")
            }
            LocalVoiceCommand.NONE -> {
                // Use the same assistant path as typed and live-chat turns.
                // Bypassing it through the legacy ActionRequest parser caused
                // learned routines and local aliases to behave differently.
                publishEvent("Voice diagnostic: assistant processing started.")
                askAssistant(
                    parsed.fallbackTextCommand ?: command
                ) { reply ->
                    liveHandler.post {
                        publishEvent("Voice diagnostic: assistant response received (${reply.text.length} characters).")
                        publishEvent("J.A.R.V.I.S.: ${reply.text}")
                        speakResponse(reply.text)
                    }
                }
            }
        }
    }

    private fun handleActionResult(result: ActionResult) {
        val status = if (result.success) "SUCCESS" else "FAIL"
        publishEvent("J.A.R.V.I.S.: [$status][${result.adapterUsed}] ${result.message}")
        result.output?.let { publishEvent("J.A.R.V.I.S.: $it") }
        voiceService.speak(if (result.success) result.message else "I could not complete that command.")
    }

    private fun persistStage(stage: String) {
        runCatching { java.io.File(filesDir, "wake-stage.txt").writeText(stage) }
    }

    private fun publishEvent(message: String) {
        if (pipelineStarted || liveChatActive) updateNotification(message)
        sendBroadcast(
            Intent(ACTION_EVENT).apply {
                setPackage(packageName)
                putExtra(EXTRA_EVENT_MESSAGE, message)
                putExtra(EXTRA_PHASE, voicePhase.name)
            }
        )
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1001,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1002,
            Intent(this, VoiceForegroundService::class.java).setAction(if (liveChatActive) ACTION_LIVE_STOP else ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (liveChatActive) "J.A.R.V.I.S. live chat" else getString(R.string.voice_service_notification_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, if (liveChatActive) "End live chat" else getString(R.string.stop_voice_service), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.voice_service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.voice_service_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun looksLikeYes(text: String): Boolean {
        val normalized = normalize(text)
        return normalized.contains("yes") || normalized.contains("correct") || normalized.contains("confirm")
    }

    private fun looksLikeNo(text: String): Boolean {
        val normalized = normalize(text)
        return normalized.contains("no") || normalized.contains("cancel") || normalized.contains("wrong")
    }

    private fun normalize(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        const val ACTION_EVENT = "com.assistant.core.action.VOICE_EVENT"
        const val EXTRA_LIVE_CHAT = "live_chat_event"
        private const val ACTION_LIVE_START = "com.assistant.core.action.START_LIVE_CHAT"
        private const val ACTION_LIVE_STOP = "com.assistant.core.action.STOP_LIVE_CHAT"
        const val EXTRA_EVENT_MESSAGE = "event_message"
        const val EXTRA_PHASE = "voice_phase"
        private const val ACTION_PUSH_TO_TALK = "com.assistant.core.action.CAPTURE_VOICE_COMMAND"
        private const val ACTION_FINISH_CAPTURE = "com.assistant.core.action.FINISH_VOICE_COMMAND"
        private const val ACTION_CANCEL_CAPTURE = "com.assistant.core.action.CANCEL_VOICE_COMMAND"
        fun captureCommand(context: Context, resumeWake: Boolean) {
            ContextCompat.startForegroundService(context, Intent(context, VoiceForegroundService::class.java)
                .setAction(ACTION_PUSH_TO_TALK).putExtra("resume_wake", resumeWake))
        }
        fun finishCapture(context: Context) { context.startService(Intent(context, VoiceForegroundService::class.java).setAction(ACTION_FINISH_CAPTURE)) }
        fun cancelCapture(context: Context) { context.startService(Intent(context, VoiceForegroundService::class.java).setAction(ACTION_CANCEL_CAPTURE)) }

        private const val CHANNEL_ID = "jarvis_voice_service"
        private const val NOTIFICATION_ID = 42001

        private const val ACTION_PRACTICE_PAUSE = "com.assistant.core.action.PAUSE_VOICE_FOR_PRACTICE"
        private const val ACTION_PRACTICE_RELEASE = "com.assistant.core.action.RELEASE_VOICE_PRACTICE"

        fun pauseForPractice(context: Context, token: String) {
            context.startService(Intent(context, VoiceForegroundService::class.java).setAction(ACTION_PRACTICE_PAUSE).putExtra("token", token))
        }
        fun releasePractice(context: Context, token: String) {
            context.startService(Intent(context, VoiceForegroundService::class.java).setAction(ACTION_PRACTICE_RELEASE).putExtra("token", token))
        }

        private const val ACTION_START = "com.assistant.core.action.START_VOICE_SERVICE"
        private const val ACTION_STOP = "com.assistant.core.action.STOP_VOICE_SERVICE"

        fun startLiveChat(context: Context, conversation: String, resumeWake: Boolean) {
            ContextCompat.startForegroundService(context, Intent(context, VoiceForegroundService::class.java)
                .setAction(ACTION_LIVE_START).putExtra("conversation", conversation).putExtra("resume_wake", resumeWake))
        }
        fun stopLiveChat(context: Context) {
            context.startService(Intent(context, VoiceForegroundService::class.java).setAction(ACTION_LIVE_STOP))
        }
        fun start(context: Context, announce: Boolean = false) {
            val intent = Intent(context, VoiceForegroundService::class.java).setAction(ACTION_START).putExtra("announce", announce)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoiceForegroundService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
