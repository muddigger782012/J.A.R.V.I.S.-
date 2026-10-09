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
    private lateinit var capabilityState: CapabilityState
    private lateinit var currentConfig: VoiceConfig

    private var pendingClarification: String? = null

    override fun onCreate() {
        super.onCreate()
        voicePreferences = VoicePreferences(this)
        initializeEngine()
        initializeVoice()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVoicePipeline()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification("Voice service is active"))
                startVoicePipeline()
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopVoicePipeline()
        voiceService.shutdown()
        voicePreferences.setForegroundServiceRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun initializeEngine() {
        val database = Database(this)
        val projectRepository = ProjectRepository(database)
        val actionRepository = ActionRepository(database)
        val auditRepository = AuditRepository(database)
        val fileService = FileService(this)
        val codingService = CodingService(this, fileService)
        val systemService = SystemService(this)
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
    }

    private fun initializeVoice() {
        voiceService = VoiceAssistantService(
            context = this,
            onStatus = { status -> publishEvent(status) },
            onHotwordDetected = { publishEvent("Hotword detected in background mode.") },
            onCommandDetected = { recognition ->
                processRecognition(recognition)
            }
        )
    }

    private fun startVoicePipeline() {
        currentConfig = voicePreferences.load()
        voiceService.updateConfig(currentConfig)
        voiceService.startHotwordLoop()
        voicePreferences.setForegroundServiceRunning(true)
        publishEvent("Foreground voice service started.")
    }

    private fun stopVoicePipeline() {
        voiceService.stopListening()
        pendingClarification = null
        voicePreferences.setForegroundServiceRunning(false)
        publishEvent("Foreground voice service stopped.")
    }

    private fun processRecognition(recognition: VoiceRecognitionResult) {
        currentConfig = voicePreferences.load()
        val transcript = recognition.transcript
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
                val result = parsed.actionRequest?.let { assistantEngine.executeAction(it) }
                    ?: assistantEngine.handleUserCommand(parsed.fallbackTextCommand ?: command)
                handleActionResult(result)
            }
        }
    }

    private fun handleActionResult(result: ActionResult) {
        val status = if (result.success) "SUCCESS" else "FAIL"
        publishEvent("[$status][${result.adapterUsed}] ${result.message}")
        result.output?.let { publishEvent(it) }
        voiceService.speak(if (result.success) result.message else "I could not complete that command.")
    }

    private fun publishEvent(message: String) {
        updateNotification(message)
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
            Intent(this, VoiceForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.voice_service_notification_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.stop_voice_service), stopIntent)
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
        const val EXTRA_EVENT_MESSAGE = "event_message"

        private const val CHANNEL_ID = "jarvis_voice_service"
        private const val NOTIFICATION_ID = 42001

        private const val ACTION_START = "com.assistant.core.action.START_VOICE_SERVICE"
        private const val ACTION_STOP = "com.assistant.core.action.STOP_VOICE_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, VoiceForegroundService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoiceForegroundService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
