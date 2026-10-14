package com.assistant.core.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
    private var appliedVoiceConfig: VoiceConfig? = null
    private var liveChatActive = false
    private var resumeWakeAfterLive = false
    private var liveConversation = ""
    private var liveConversationToken: ConversationContext.Token? = null
    private var liveRecordSession = ""
    private val dialogueOverlay by lazy { LiveChatDialogueOverlay(this) { finishLiveChat("Live chat ended.") } }
    private val responseOptionsReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { dialogueOverlay.refresh(liveChatActive) }
    }
    private val liveSession = LiveChatSession()
    private val liveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val liveCapture by lazy { LiveSpeechCapture(this) }
    private var liveMessage = ""
    private var livePhase = VoiceInteractionState.IDLE
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!liveChatActive) return
            if (liveConversationToken?.let { hybridAssistantService.isConversationCurrent(it) } != true) {
                finishLiveChat("Live chat ended for the selected conversation.")
                return
            }
            LiveChatStore.state(this@VoiceForegroundService, true, livePhase, liveMessage)
            dialogueOverlay.refresh(true)
            liveHandler.postDelayed(this, 30000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Keep Service.onCreate lightweight. A failure in Sherpa/assistant
        // construction here occurs before onStartCommand and bypasses its
        // crash containment entirely.
        voicePreferences = VoicePreferences(this)
        ContextCompat.registerReceiver(this, responseOptionsReceiver, android.content.IntentFilter(VoiceResponsePreferences.ACTION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
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
                startForeground(NOTIFICATION_ID, buildNotification("Starting live chat…"))
                if (!liveChatActive) {
                    try {
                        resumeWakeAfterLive = pipelineStarted || intent.getBooleanExtra("resume_wake", false)
                        liveConversation = intent.getStringExtra("conversation").orEmpty()
                        liveRecordSession = java.util.UUID.randomUUID().toString()
                        LiveChatStore.lastReply(this, org.json.JSONObject())
                        if (!::voiceService.isInitialized) { initializeEngine(); initializeVoice() }
                        val selected = LiveChatStore.currentConversation(this)
                        if (selected.isBlank()) LiveChatStore.currentConversation(this, liveConversation)
                        liveConversationToken = hybridAssistantService.conversationToken()
                        if (liveConversationToken?.id != liveConversation) throw IllegalStateException("Conversation changed")
                        voiceService.stopListening()
                        voiceService.stopSpeaking()
                        pipelineStarted = false
                        voicePreferences.setForegroundServiceRunning(false)
                        currentConfig = voicePreferences.load()
                        voiceService.updateConfig(currentConfig)
                        liveChatActive = true
                        liveSession.start()
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
                return START_NOT_STICKY
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
                startForeground(NOTIFICATION_ID, buildNotification("Voice service is active"))
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
                        persistStage("failed-" + t.javaClass.simpleName + "-" + (t.message ?: "unknown"))
                        voicePreferences.setForegroundServiceRunning(false)
                        publishEvent("Foreground wake pipeline failed: " + t.javaClass.simpleName + ": " + (t.message ?: "unknown error"))
                    }
                } else {
                    val updatedConfig = voicePreferences.load()
                    if (updatedConfig != appliedVoiceConfig) {
                        currentConfig = updatedConfig
                        appliedVoiceConfig = updatedConfig
                        voiceService.updateConfig(updatedConfig)
                        publishEvent("Voice settings applied; wake sensitivity updated.")
                    } else {
                        publishEvent("Foreground voice service already active; keeping wake listener armed.")
                    }
                }
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(responseOptionsReceiver) }
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
            stopVoicePipeline()
            voiceService.shutdown()
        } else {
            pipelineStarted = false
            voicePreferences.setForegroundServiceRunning(false)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun liveStatus(phase: VoiceInteractionState, message: String) {
        livePhase = phase
        liveMessage = message
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
        voiceService.speak(text, SpeechChannel.LIVE_CHAT)
    }

    private fun nextLiveTurn(delay: Long) {
        val token = liveSession.token
        val responseContext = liveConversationToken ?: return
        liveHandler.postDelayed({
            if (!liveChatActive || liveSession.token != token) return@postDelayed
            if (!hybridAssistantService.isConversationCurrent(responseContext)) {
                finishLiveChat("Live chat ended for the selected conversation.")
                return@postDelayed
            }
            val turn = liveSession.listen() ?: return@postDelayed
            liveStatus(VoiceInteractionState.LISTENING, "Listening. Say “end live chat” to finish.")
            liveCapture.listen(currentConfig.preferOfflineCommandRecognition,
                onPartial = { /* Partial transcripts stay transient; only complete turns enter chat. */ },
                onResult = { text ->
                    if (liveChatActive && liveSession.token == turn && !hybridAssistantService.isConversationCurrent(responseContext)) {
                        finishLiveChat("Live chat ended for the selected conversation.")
                    } else if (liveChatActive && liveSession.transcript(turn)) {
                        liveLine("You: $text")
                        if (LiveChatCommands.parse(text) == LiveChatCommands.Control.STOP) {
                            finishLiveChat("Live chat ended.")
                        } else if (LiveChatCommands.parse(text) == LiveChatCommands.Control.START) {
                            liveSession.response(turn)
                            liveLine("J.A.R.V.I.S.: Live chat is already active.")
                            speakLiveReply("Live chat is already active.")
                        } else {
                            liveStatus(VoiceInteractionState.PROCESSING, "Thinking…")
                            hybridAssistantService.handleUserInputAsync(text) { reply ->
                                liveHandler.post {
                                    if (liveChatActive && hybridAssistantService.isConversationCurrent(responseContext) && liveSession.response(turn)) {
                                        liveLine("J.A.R.V.I.S.: ${reply.text}")
                                        speakLiveReply(reply.text)
                                    }
                                }
                            }
                            liveHandler.postDelayed({
                                if (liveChatActive && liveSession.token == turn && liveSession.phase == LiveChatSession.Phase.THINKING)
                                    finishLiveChat("The reply took too long. Start live chat again to retry.")
                            }, 60000L)
                        }
                    }
                },
                onError = { error -> liveCaptureError(turn, error) })
            liveHandler.postDelayed({
                if (liveChatActive && liveSession.token == turn && liveSession.phase == LiveChatSession.Phase.LISTENING) {
                    liveCapture.stop()
                    liveCaptureError(turn, android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                }
            }, 25000L)
        }, delay)
    }

    private fun liveCaptureError(turn: Long, error: Int) {
        if (!liveChatActive || liveSession.token != turn || liveSession.phase != LiveChatSession.Phase.LISTENING) return
        if (error == android.speech.SpeechRecognizer.ERROR_NO_MATCH || error == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            if (liveSession.silence(turn)) {
                liveStatus(VoiceInteractionState.LISTENING, "No speech heard. Listening again…")
                nextLiveTurn(1000L)
            } else finishLiveChat("Live chat ended after no speech was heard.")
        } else {
            val message = when (error) {
                android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow microphone permission to use live chat."
                android.speech.SpeechRecognizer.ERROR_NETWORK, android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition lost its connection."
                android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The microphone is busy."
                else -> "Speech recognition stopped ($error)."
            }
            finishLiveChat(message)
        }
    }

    private fun finishLiveChat(message: String) {
        dialogueOverlay.hide()
        liveChatActive = false
        liveConversationToken = null
        liveSession.stop()
        liveHandler.removeCallbacksAndMessages(null)
        liveCapture.stop()
        if (::voiceService.isInitialized) voiceService.stopSpeaking()
        liveStatus(VoiceInteractionState.IDLE, message)
        if (resumeWakeAfterLive && ::voiceService.isInitialized) {
            resumeWakeAfterLive = false
            startVoicePipeline()
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
            onStateChanged = { state -> publishEvent("Voice state: ${state.userLabel()}") },
            onTranscript = { transcript -> processTranscript(transcript) },
            onError = { failure -> publishEvent("Voice error: ${failure.message}") }
        )
        voiceService = VoiceAssistantService(
            context = this,
            onStatus = { status -> publishEvent(status) },
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
            onSpeechStarted = {
                publishEvent("Voice diagnostic: spoken response playback started.")
                voiceSession.speaking()
            },
            onSpeechFinished = {
                publishEvent("Voice diagnostic: spoken response playback finished.")
                if (liveChatActive) {
                    if (liveSession.speechFinished()) nextLiveTurn(650L)
                } else {
                    voiceSession.responseFinished(keepArmed = true)
                    voiceService.rearmAfterResponse()
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
        voiceSession.arm()
        if (announce) voiceService.startWithIntroduction() else voiceService.startHotwordLoop()
        pipelineStarted = true
        voicePreferences.setForegroundServiceRunning(true)
        publishEvent("Foreground voice service started.")
    }

    private fun stopVoicePipeline() {
        voiceService.stopListening()
        pipelineStarted = false
        voiceSession.stop()
        pendingClarification = null
        voicePreferences.setForegroundServiceRunning(false)
        publishEvent("Foreground voice service stopped.")
    }

    private fun processTranscript(transcript: String) {
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
        val responseContext = hybridAssistantService.conversationToken()
        hybridAssistantService.handleUserInputAsync(transcript) { reply ->
            liveHandler.post {
                if (!hybridAssistantService.isConversationCurrent(responseContext)) return@post
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
                val responseContext = hybridAssistantService.conversationToken()
                hybridAssistantService.handleUserInputAsync(
                    parsed.fallbackTextCommand ?: command
                ) { reply ->
                    liveHandler.post {
                        if (!hybridAssistantService.isConversationCurrent(responseContext)) return@post
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

