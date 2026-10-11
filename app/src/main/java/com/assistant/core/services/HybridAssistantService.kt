package com.assistant.core.services

import com.assistant.core.engine.ActionRegistry
import com.assistant.core.engine.AssistantEngine
import com.assistant.core.models.ActionResult
import com.assistant.core.models.CapabilityState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

data class HybridAssistantReply(
    val text: String,
    val actionResult: ActionResult? = null
)

private enum class PendingPrompt {
    NONE,
    PROJECT_NAME,
    SHELL_CONFIRMATION,
    REBOOT_CONFIRMATION
}

class HybridAssistantService(
    private val assistantEngine: AssistantEngine,
    private val actionRegistry: ActionRegistry,
    private val systemService: SystemService,
    private val capabilityProvider: () -> CapabilityState
) {

    private var pendingPrompt: PendingPrompt = PendingPrompt.NONE
    private var pendingLearningReset: Boolean = false
    private var pendingShellCommand: String? = null
    private var lastContactTarget: String? = null
    private val history = ArrayDeque<Pair<String, String>>()
    private val androidAssistant = AndroidAssistantService(systemService.context())
    private val learningStore = IntentLearningStore(systemService.context())
    private val directOnlineClient = DirectOnlineClient(systemService.context())
    private val assistantConnections = AssistantConnections(systemService.context())
    private val fallbackAiClient = FallbackAiClient(systemService.context())

    fun handleUserInput(rawInput: String): HybridAssistantReply {
        val userInput = rawInput.trim()
        val normalized = normalize(userInput)
        if (userInput.isBlank()) {
            val result = assistantEngine.executeAction(actionRegistry.showStatusRequest())
            return replyFromResult(
                userText = userInput,
                preface = "Here is your current system status.",
                result = result
            )
        }

        when (pendingPrompt) {
            PendingPrompt.PROJECT_NAME -> {
                pendingPrompt = PendingPrompt.NONE
                val name = sanitizeProjectName(userInput)
                val result = assistantEngine.executeAction(actionRegistry.createProjectRequest(name))
                return replyFromResult(
                    userText = userInput,
                    preface = "Great. Creating project \"$name\" now.",
                    result = result
                )
            }
            PendingPrompt.SHELL_CONFIRMATION -> {
                pendingPrompt = PendingPrompt.NONE
                val command = pendingShellCommand
                pendingShellCommand = null
                return if (looksLikeYes(normalized) && !command.isNullOrBlank()) {
                    replyFromResult(userInput, "Executing confirmed privileged command.", assistantEngine.executeAction(actionRegistry.runShellRequest(command, confirmed = true)))
                } else {
                    remember(userInput, "Privileged command cancelled.")
                    HybridAssistantReply("Privileged command cancelled.")
                }
            }
            PendingPrompt.REBOOT_CONFIRMATION -> {
                pendingPrompt = PendingPrompt.NONE
                return if (looksLikeYes(normalized)) {
                    val result = assistantEngine.executeAction(actionRegistry.rebootDeviceRequest(confirmed = true))
                    replyFromResult(
                        userText = userInput,
                        preface = "Understood. Executing reboot request.",
                        result = result
                    )
                } else {
                    remember(userInput, "Reboot cancelled.")
                    HybridAssistantReply("Reboot cancelled. I won't make any system changes.")
                }
            }
            PendingPrompt.NONE -> Unit
        }

        // Route the built-in updater locally. Never let cloud AI claim it installed a shortcut.
        val updaterTeaching = LocalDeviceCommand.isUpdaterTeaching(userInput)
        if (updaterTeaching) {
            val response = "The command \"update app\" is built in. Say \"update app\" to open the J.A.R.V.I.S. updater."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        if (LocalDeviceCommand.parse(userInput) == LocalDeviceCommand.Action.UPDATE_APP) {
            val launched = runCatching {
                val context = systemService.context()
                val intent = android.content.Intent(context, com.assistant.core.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("jarvis_feature", "app_update")
                context.startActivity(intent)
            }.isSuccess
            val response = if (launched) "Opening the J.A.R.V.I.S. updater. Android will ask you to approve installation." else "Could not open the updater. Open J.A.R.V.I.S., then go to Tools > System status and updates."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        // Explicit, on-device teaching for the verified clock capability.
        // This never authorizes arbitrary device actions or cloud-generated code.
        val teachTime = Regex("(?i)^teach (?:jarvis )?(?:that )?(.{1,200}?) (?:means|is the same as) (?:what time is it|tell me the time)[?.!]*$").matchEntire(userInput)
        if (teachTime != null) {
            val phrase = teachTime.groupValues[1].trim()
            val reserved = normalize(phrase) in setOf("learning status", "clear local learning", "confirm learning reset")
            val response = if (reserved || phrase.length < 3) {
                "That phrase is reserved or too short to learn."
            } else {
                learningStore.learn(phrase, "GENERAL_QUESTION", source = "verified_local_time")
                if (learningStore.classify(phrase)?.let { it.intent == "GENERAL_QUESTION" && it.source == "verified_local_time" && it.confidence == 1.0 } == true)
                    "Learned on this device: \"$phrase\" now requests the current time. Say the phrase to test it."
                else "Could not learn that phrase because it conflicts with an existing lesson."
            }
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        // Learn only allowlisted volume actions, and only after Android confirms execution.
        // Exact phrase matches avoid fuzzy routing of unrelated or sensitive commands.
        val volumeCapabilities = setOf("volume_up", "volume_down")
        val volumeMemory = com.assistant.core.learning.ValidatedCommandMemory(systemService.context())
        val teachVolume = Regex("(?i)^teach (?:jarvis )?(?:that )?(.{3,160}?) (?:means|is the same as) (?:turn (?:the )?volume up|increase (?:the )?volume|turn (?:the )?volume down|decrease (?:the )?volume)[?.!]*$").matchEntire(userInput)
        if (teachVolume != null) {
            val phrase = teachVolume.groupValues[1].trim()
            val target = normalize(userInput).substringAfterLast(" means ", "").ifBlank {
                normalize(userInput).substringAfterLast(" is the same as ", "")
            }.trimEnd('?', '.', '!')
            val capability = if (target.endsWith("up") || target.startsWith("increase ")) "volume_up" else "volume_down"
            val reserved = phrase.startsWith("teach ", ignoreCase = true) ||
                normalize(phrase).matches(Regex(".*\\b(?:call|text|message|delete|reset|reboot|pay|purchase|send)\\b.*")) ||
                normalize(phrase) in setOf("learning status", "clear local learning", "confirm learning reset", "cancel learning reset")
            val response = if (reserved) {
                "That phrase is reserved and cannot be learned."
            } else {
                val direction = if (capability == "volume_up") android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER
                val executed = androidAssistant.adjustVolume(direction)
                if (!executed) "Android could not adjust the volume, so the command was not learned."
                else if (volumeMemory.recordSuccess(phrase, capability, volumeCapabilities))
                    "Volume adjusted and verified. Learned on this device: \"$phrase\"."
                else "Volume adjusted, but the phrase could not be learned because it conflicts with another command."
            }
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        // Teach aliases only for known, allowlisted local actions. An alias is stored
        // only after the corresponding action succeeds on the device.
        val teachDeviceAlias = Regex(
            "(?i)^teach (?:jarvis )?(?:that )?(.{3,160}?) (?:means|is the same as) " +
                "(update app|update jarvis|upgrade app|upgrade jarvis|check for jarvis update)[?.!]*$"
        ).matchEntire(userInput)
        if (teachDeviceAlias != null) {
            val alias = teachDeviceAlias.groupValues[1].trim()
            val target = teachDeviceAlias.groupValues[2]
            val reserved = LocalDeviceCommand.parse(alias) != null ||
                alias.startsWith("teach ", ignoreCase = true) ||
                normalize(alias).matches(Regex(".*\\b(?:call|text|message|delete|reset|reboot|pay|purchase|send)\\b.*"))
            val response = if (reserved) {
                "That phrase is reserved and cannot be learned."
            } else {
                val context = systemService.context()
                val launched = runCatching {
                    val intent = android.content.Intent(context, com.assistant.core.MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("jarvis_feature", "app_update")
                    context.startActivity(intent)
                }.isSuccess
                if (!launched) "Could not open the updater, so the alias was not learned."
                else if (volumeMemory.recordSuccess(alias, "app_update", volumeCapabilities + "app_update"))
                    "Opened the updater and learned on this device: \\"$alias\\" means \\"$target\\"."
                else "Opened the updater, but the phrase conflicts with an existing learned command."
            }
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        volumeMemory.match(userInput, volumeCapabilities + "app_update")?.let { learnedUpdater ->
            if (learnedUpdater.capabilityId == "app_update") {
                val launched = runCatching {
                    val context = systemService.context()
                    context.startActivity(android.content.Intent(context, com.assistant.core.MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("jarvis_feature", "app_update"))
                }.isSuccess
                val response = if (launched) "Opening the J.A.R.V.I.S. updater using your learned command."
                    else "Could not open the updater."
                remember(userInput, response)
                return HybridAssistantReply(response)
            }
        }

        volumeMemory.match(userInput, volumeCapabilities)?.let { learned ->
            val direction = if (learned.capabilityId == "volume_up") android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER
            val executed = androidAssistant.adjustVolume(direction)
            val response = if (executed) "Volume adjusted using your learned command." else "Android could not adjust the volume."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        // Learning diagnostics are handled locally, without an AI connection.
        if (normalized in setOf("learning status", "show learning status", "how many commands have you learned", "learned commands")) {
            val commandCount = com.assistant.core.learning.ValidatedCommandMemory(systemService.context()).learnedPhraseCount()
            val response = "Local learned intent examples: ${learningStore.count()}; validated command phrases: $commandCount. Say 'forget learned command' followed by the exact phrase, or 'clear learned database on this device' to erase both local stores."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        if (normalized.startsWith("forget learned command ")) {
            val phrase = userInput.replace(Regex("(?i)^forget learned command\\s+"), "").trim()
            val removedIntent = phrase.isNotBlank() && learningStore.forget(phrase)
            val removedCommand = phrase.isNotBlank() && com.assistant.core.learning.ValidatedCommandMemory(systemService.context()).forget(phrase)
            val response = if (removedIntent || removedCommand) "Removed matching local learning for that phrase." else "No matching learned command was found."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        if (normalized in setOf("reset learned commands", "reset learned commands on this device", "clear learned database on this device", "clear local learning")) {
            pendingLearningReset = true
            val response = "This will erase learned commands stored on this device only. Other devices are not affected. Say 'confirm learning reset on this device' to continue, or 'cancel learning reset' to keep them."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        if (normalized in setOf("confirm learning reset", "confirm learning reset on this device", "cancel learning reset")) {
            val response = when {
                !pendingLearningReset -> "No learning reset is pending."
                normalized == "cancel learning reset" -> "Learning reset cancelled."
                else -> {
                    val intentsCleared = learningStore.clear()
                    val commandsCleared = com.assistant.core.learning.ValidatedCommandMemory(systemService.context()).clear()
                    if (intentsCleared && commandsCleared) {
                        "Learned commands on this device have been cleared. Other devices are unchanged."
                    } else {
                        "Could not clear local learning. Please try again."
                    }
                }
            }
            pendingLearningReset = false
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        // A reset confirmation is valid only for the immediately following command.
        // An unrelated command cancels it to avoid accidental deletion later.
        pendingLearningReset = false

        if (CommandPhrases.isLastMissedCall(userInput)) {
            val response = androidAssistant.lastMissedCall()
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        if (containsAny(normalized, "hello", "hi jarvis", "hey jarvis", "good morning", "good evening")) {
            val response = "Hello. I can help with calls, messages, maps, weather, music, and questions."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        if (containsAny(normalized, "what can you do", "help", "capabilities", "features")) {
            val response = buildString {
                appendLine("I can help with:")
                appendLine("- Call a number or contact with Phone permission")
                appendLine("- Read your latest missed call with Call Log permission")
                appendLine("- Open maps, apps, and compose messages")
                appendLine("- Set alarms and control music")
                appendLine("- Get US weather directly, without an AI key")
                appendLine("- Answer general questions with your optional Gemini key")
                appendLine("No custom server or companion app is needed for built-in commands.")
                appendLine("Advanced tools and home-server connections are optional.")
            }
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        // Only verified time examples can reuse the local time handler.
        // A generic GENERAL_QUESTION label must never authorize device actions.
        val learnedTime = learningStore.classify(userInput)?.let {
            it.intent == "GENERAL_QUESTION" &&
                it.source == "verified_local_time" &&
                it.confidence >= 0.99
        } == true
        if (isTimeRequest(normalized) || learnedTime) {
            val response = "It's " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()) + "."
            if (isTimeRequest(normalized)) {
                learningStore.learn(userInput, "GENERAL_QUESTION", source = "verified_local_time")
            }
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        LocalKnowledge.reply(userInput)?.let { response ->
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        calendarRangeFor(normalized)?.let { range ->
            if (!androidAssistant.hasCalendarReadPermission()) {
                val response = "Calendar permission is required. Open J.A.R.V.I.S. and grant Calendar access, then ask again."
                remember(userInput, response)
                return HybridAssistantReply(response)
            }
            val events = androidAssistant.calendarEntries(range.first, range.second)
            val response = if (events.isEmpty()) {
                "You don't have any calendar events in that period."
            } else {
                val format = SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.getDefault())
                events.joinToString(prefix = "Your calendar: ", separator = "; ") {
                    "${it.title}, ${format.format(Date(it.beginMillis))}"
                }
            }
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        parseTimerSeconds(normalized)?.let { seconds ->
            val ok = androidAssistant.setTimer(seconds)
            val response = if (ok) "Timer set for $seconds seconds." else "I couldn't open the Android timer service."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        parseNavigationDestination(normalized)?.let { destination ->
            val ok = androidAssistant.navigate(destination)
            val response = if (ok) "Starting navigation to $destination." else "I couldn't start navigation."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        if (LocalDeviceCommand.parse(userInput) == LocalDeviceCommand.Action.VOLUME_UP) {
            val ok = androidAssistant.adjustVolume(android.media.AudioManager.ADJUST_RAISE)
            val response = if (ok) "Volume increased." else "I couldn't change the volume."
            remember(userInput, response); return HybridAssistantReply(response)
        }
        if (LocalDeviceCommand.parse(userInput) == LocalDeviceCommand.Action.VOLUME_DOWN) {
            val ok = androidAssistant.adjustVolume(android.media.AudioManager.ADJUST_LOWER)
            val response = if (ok) "Volume decreased." else "I couldn't change the volume."
            remember(userInput, response); return HybridAssistantReply(response)
        }
        parseCalendarRequest(userInput)?.let { title ->
            val ok = androidAssistant.createCalendarEvent(title)
            val response = if (ok) "Opening your calendar to create $title." else "I couldn't open the calendar."
            remember(userInput, response); return HybridAssistantReply(response)
        }
        parseReminderRequest(userInput)?.let { title ->
            val ok = androidAssistant.createReminder(title)
            val response = if (ok) "Opening your calendar to create the reminder $title." else "I couldn't open a reminder provider."
            remember(userInput, response); return HybridAssistantReply(response)
        }
        if (normalized in setOf("notification settings", "open notification settings", "manage notifications")) {
            val ok = androidAssistant.openNotificationSettings()
            val response = if (ok) "Opening J.A.R.V.I.S. notification settings." else "I couldn't open notification settings."
            remember(userInput, response); return HybridAssistantReply(response)
        }

        parseCallTarget(normalized)?.let { rawTarget ->
            val target = cleanContactTarget(resolveConversationContact(rawTarget))
            lastContactTarget = target
            val directNumber = target.filter { it.isDigit() || it == '+' }.takeIf { it.any(Char::isDigit) }
            if (directNumber == null && !hasContactsPermission()) {
                val response = "I need Contacts permission before I can look up $target. Please allow Contacts access for J.A.R.V.I.S., then try again."
                remember(userInput, response); return HybridAssistantReply(response)
            }
            val number = directNumber ?: androidAssistant.resolveContactPhone(target)
            val dialOnly = normalized.startsWith("dial ")
            if (!dialOnly && number != null && !androidAssistant.hasCallPermission()) {
                val response = "Phone permission is required to place calls. Open J.A.R.V.I.S. Permissions, grant Phone access, then say call $target again."
                remember(userInput, response)
                return HybridAssistantReply(response)
            }
            val ok = number?.let { if (dialOnly) androidAssistant.dial(it) else androidAssistant.call(it) } ?: false
            val response = when {
                ok && dialOnly -> "Opening the dialer for $target."
                ok -> "Starting the call to $target."
                number == null -> "I couldn't find $target in your contacts with a phone number."
                else -> "Android couldn't start the call. Check Phone permission and your phone app."
            }
            remember(userInput, response); return HybridAssistantReply(response)
        }
        parseMessageRequest(userInput)?.let { (rawTarget, body) ->
            val target = resolveConversationContact(rawTarget)
            lastContactTarget = target
            val number = target.filter { it.isDigit() || it == '+' }.takeIf { it.any(Char::isDigit) }
                ?: androidAssistant.resolveContactPhone(target)
            val ok = number?.let { androidAssistant.composeSms(it, body) } ?: false
            val response = if (ok) "Opening your messaging app with the message ready for $target." else "I couldn't find a phone number for $target. Contact access may be required."
            remember(userInput, response); return HybridAssistantReply(response)
        }
        parseAlarm(normalized)?.let { (hour, minute) ->
            val ok = androidAssistant.setAlarm(hour, minute)
            val response = if (ok) "Alarm set." else "I couldn't open the Android alarm service."
            remember(userInput, response); return HybridAssistantReply(response)
        }

        if (CommandPhrases.isAmazonMusicRequest(userInput)) {
            val response = androidAssistant.amazonMusic(Regex("(?i)\\bplay\\b").containsMatchIn(userInput))
            remember(userInput, response); return HybridAssistantReply(response)
        }

        parseAppName(normalized)?.let { appName ->
            val ok = androidAssistant.launchAppByLabel(appName)
            val response = if (ok) "Opening $appName." else "I couldn't find an installed app named $appName."
            remember(userInput, response); return HybridAssistantReply(response)
        }
        if (normalized in setOf("play", "pause", "play music", "pause music", "play pause", "resume music")) {
            val ok = androidAssistant.playPauseMedia()
            val response = if (ok) "Media control sent." else "I couldn't control media playback."
            remember(userInput, response); return HybridAssistantReply(response)
        }
        if (normalized in setOf("next", "next song", "next track", "skip song", "skip track")) {
            val ok = androidAssistant.nextMedia()
            val response = if (ok) "Skipping to the next track." else "I couldn't control media playback."
            remember(userInput, response); return HybridAssistantReply(response)
        }
        if (normalized in setOf("previous song", "previous track", "go back a track")) {
            val ok = androidAssistant.previousMedia()
            val response = if (ok) "Going to the previous track." else "I couldn't control media playback."
            remember(userInput, response); return HybridAssistantReply(response)
        }

        if (normalized in setOf("open settings", "device settings", "system settings")) {
            val ok = androidAssistant.openSettings()
            val response = if (ok) "Opening Android settings." else "I couldn't open Android settings."
            remember(userInput, response); return HybridAssistantReply(response)
        }

        if (normalized in setOf("go back", "back")) {
            return replyFromResult(userInput, "Going back.", assistantEngine.executeAction(actionRegistry.accessibilityGlobalRequest(ActionRegistry.ACCESSIBILITY_BACK)))
        }
        if (normalized in setOf("go home", "home screen", "home")) {
            return replyFromResult(userInput, "Going home.", assistantEngine.executeAction(actionRegistry.accessibilityGlobalRequest(ActionRegistry.ACCESSIBILITY_HOME)))
        }
        if (normalized in setOf("show recent apps", "recent apps", "recents")) {
            return replyFromResult(userInput, "Opening recent apps.", assistantEngine.executeAction(actionRegistry.accessibilityGlobalRequest(ActionRegistry.ACCESSIBILITY_RECENTS)))
        }
        if (containsAny(normalized, "what is on my screen", "whats on my screen", "read my screen", "read screen", "screen context")) {
            return replyFromResult(userInput, "Reading the current screen.", assistantEngine.executeAction(actionRegistry.readScreenRequest()))
        }

        if (containsAny(normalized, "create project", "new project", "generate project", "make project")) {
            val extracted = extractProjectName(normalized)
            if (extracted == null) {
                pendingPrompt = PendingPrompt.PROJECT_NAME
                val response = "Sure. What should I name the new project?"
                remember(userInput, response)
                return HybridAssistantReply(response)
            }
            val result = assistantEngine.executeAction(actionRegistry.createProjectRequest(extracted))
            return replyFromResult(
                userText = userInput,
                preface = "Creating project \"$extracted\".",
                result = result
            )
        }

        if (containsAny(normalized, "run shell", "execute shell", "run command")) {
            val command = extractShellCommand(userInput)
            pendingShellCommand = command
            pendingPrompt = PendingPrompt.SHELL_CONFIRMATION
            val response = "This privileged command requires a separate confirmation before execution."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        if (containsAny(normalized, "status", "system status", "show status")) {
            val result = assistantEngine.executeAction(actionRegistry.showStatusRequest())
            return replyFromResult(
                userText = userInput,
                preface = "Here is your status summary.",
                result = result
            )
        }

        if (containsAny(normalized, "reboot", "restart device")) {
            pendingPrompt = PendingPrompt.REBOOT_CONFIRMATION
            val response = "Reboot is a high-risk action. Confirm by saying \"yes\" or \"confirm reboot\"."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        // Learned intent labels are advisory until a vetted capability executor
        // exists. Never short-circuit cloud fallback with a non-actionable label.
        // This prevents previously learned phrases from becoming dead ends.

        val fallback = assistantEngine.handleUserCommand(userInput)
        if (!fallback.success && fallback.adapterUsed == "ENGINE") {
            val response = buildString {
                appendLine("I don't understand that locally yet.")
                appendLine("This request is eligible for fallback-AI interpretation and validated local learning.")
                append("Local learned examples: ${learningStore.count()}")
            }
            remember(userInput, response)
            return HybridAssistantReply(response, fallback)
        }
        return replyFromResult(
            userText = userInput,
            preface = "Done.",
            result = fallback
        )
    }

    fun handleUserInputAsync(rawInput: String, callback: (HybridAssistantReply) -> Unit) {
        AssistantConnectionProtocol.parse(rawInput)?.let { request ->
            Thread {
                val response = assistantConnections.ask(request)
                remember(rawInput, response)
                callback(HybridAssistantReply(response))
            }.start()
            return
        }
        val local = handleUserInput(rawInput)
        val unresolved = local.text.startsWith("I don't understand that locally yet.")
        if (unresolved && OnlineAnswers.isWeather(rawInput)) {
            Thread {
                val response = directOnlineClient.weather(rawInput)
                remember(rawInput, response)
                callback(HybridAssistantReply(response))
            }.start()
            return
        }
        if (!unresolved) {
            callback(local)
            return
        }
        if (directOnlineClient.hasGeminiKey()) {
            Thread {
                val response = directOnlineClient.gemini(rawInput, getConversationSnapshot())
                remember(rawInput, response)
                callback(HybridAssistantReply(response))
            }.start()
            return
        }
        if (!fallbackAiClient.isConfigured()) {
            val response = "I don't understand that locally yet. AI fallback needs configuration. Enter your Gemini API key in Voice Settings → Weather and AI."
            remember(rawInput, response)
            callback(HybridAssistantReply(response, local.actionResult))
            return
        }
        Thread {
            val cloud = fallbackAiClient.ask(rawInput)
            if (cloud == null) {
                val response = "I don't understand that locally yet. Cloud fallback failed or returned no usable reply. Check your connection and AI settings."
                remember(rawInput, response)
                callback(HybridAssistantReply(response, local.actionResult))
                return@Thread
            }
            // A plausible cloud interpretation is not proof of a successful action.
            // Do not train device-action intents from unverified cloud replies.
            cloud.lessonJson?.let { acceptFallbackLesson(rawInput, it) }
            remember(rawInput, cloud.reply)
            callback(HybridAssistantReply(cloud.reply))
        }.start()
    }

    /**
     * Accept a structured interpretation from the fallback AI. Only approved,
     * high-confidence lessons are persisted; execution remains behind normal
     * JARVIS capability/policy routing.
     */
    fun acceptFallbackLesson(userText: String, lessonJson: String): Boolean {
        val lesson = FallbackIntentLesson.fromJson(lessonJson) ?: return false
        if (!LearningAdmissionPolicy.canRecordWithoutActionResult(lesson)) return false
        val before = learningStore.count()
        learningStore.learn(
            utterance = userText,
            intent = lesson.intent,
            entities = lesson.entities,
            source = "validated_fallback_v${FallbackLessonProtocol.VERSION}"
        )
        return learningStore.count() > before || learningStore.classify(userText)?.let {
            it.intent == lesson.intent && it.confidence == 1.0
        } == true
    }

    @Synchronized
    fun getConversationSnapshot(limit: Int = 8): String {
        return history.filterNot { it.second.startsWith("I don’t understand that locally yet.") || it.second.startsWith("I don't understand that locally yet.") }.takeLast(limit).joinToString(separator = "\n\n") { (user, assistant) ->
            "You: $user\nJ.A.R.V.I.S.: $assistant"
        }
    }

    private fun replyFromResult(userText: String, preface: String, result: ActionResult): HybridAssistantReply {
        val reply = buildString {
            appendLine(preface)
            appendLine(result.message)
            result.output?.let {
                appendLine()
                append(it)
            }
        }.trim()
        // Record only actions that actually succeeded. Never learn a privileged
        // command, and never execute an AI-supplied label directly.
        if (result.success && result.adapterUsed != "ENGINE") {
            val safeIntent = when {
                isTimeRequest(normalize(userText)) -> "GENERAL_QUESTION"
                else -> null
            }
            if (safeIntent != null) {
                learningStore.learn(userText, safeIntent, source = "verified_local_action")
            }
        }
        remember(userText, reply)
        return HybridAssistantReply(reply, result)
    }

    @Synchronized
    private fun remember(userText: String, assistantText: String) {
        history.addLast(userText to assistantText)
        while (history.size > 30) {
            history.removeFirst()
        }
    }

    private fun isTimeRequest(text: String): Boolean {
        val cleaned = text
            .replace(Regex("\\b(?:hey\\s+)?jarvis\\b[,:]?\\s*"), "")
            .trim()
        if (cleaned in setOf(
                "time", "what time", "what time is it", "whats the time",
                "tell me the time", "current time", "what is the time"
            )) return true
        return Regex("\\b(?:what|whats|what is|tell me)\\b.*\\btime\\b").containsMatchIn(cleaned)
    }

    private fun calendarRangeFor(text: String): Pair<Long, Long>? {
        val asksCalendar = listOf(
            "calendar", "appointment", "appointments", "schedule",
            "what do i have", "whats on my", "what is on my", "next event"
        ).any { text.contains(it) }
        if (!asksCalendar) return null

        val start = Calendar.getInstance()
        val end = Calendar.getInstance()
        when {
            text.contains("tomorrow") -> {
                start.add(Calendar.DAY_OF_YEAR, 1)
                start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0); start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0)
                end.timeInMillis = start.timeInMillis
                end.add(Calendar.DAY_OF_YEAR, 1)
            }
            text.contains("next") && (text.contains("event") || text.contains("appointment")) -> {
                end.add(Calendar.DAY_OF_YEAR, 30)
            }
            else -> {
                start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0); start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0)
                end.timeInMillis = start.timeInMillis
                end.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return start.timeInMillis to end.timeInMillis
    }

    private fun parseTimerSeconds(text: String): Int? {
        val match = Regex("(?:set|start) (?:a )?timer (?:for )?(\\d+) (second|seconds|minute|minutes|hour|hours)").find(text) ?: return null
        val amount = match.groupValues[1].toIntOrNull() ?: return null
        return when (match.groupValues[2]) {
            "hour", "hours" -> amount * 3600
            "minute", "minutes" -> amount * 60
            else -> amount
        }
    }

    private fun parseCalendarRequest(raw: String): String? {
        val match = Regex("(?i)^(?:create|add|schedule) (?:a |an )?(?:calendar )?(?:event|appointment)(?: called| named)? (.+)$").find(raw.trim()) ?: return null
        return match.groupValues[1].trim().takeIf { it.isNotBlank() }
    }

    private fun parseReminderRequest(raw: String): String? {
        val match = Regex("(?i)^(?:remind me to|create (?:a )?reminder(?: to)?|add (?:a )?reminder(?: to)?) (.+)$").find(raw.trim()) ?: return null
        return match.groupValues[1].trim().takeIf { it.isNotBlank() }
    }

    private fun resolveConversationContact(target: String): String {
        val normalized = target.trim().lowercase()
        if (normalized in setOf("him", "her", "them", "that person", "the same person")) {
            return lastContactTarget ?: target
        }
        return target
    }

    private fun parseCallTarget(text: String): String? {
        val prefixes = listOf("call ", "dial ", "phone ", "ring ")
        val prefix = prefixes.firstOrNull { text.startsWith(it) } ?: return null
        return text.removePrefix(prefix).trim().takeIf { it.isNotBlank() }
    }

    private fun cleanContactTarget(target: String): String {
        return target.trim()
            .replace(Regex("^(?:contact|my contact|the contact)\\s+"), "")
            .trim()
    }

    private fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            systemService.context(),
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun parseMessageRequest(raw: String): Pair<String, String>? {
        val match = Regex("(?i)^(?:text|message)\\s+(.+?)\\s+(?:saying|say|that)\\s+(.+)$").find(raw.trim()) ?: return null
        return match.groupValues[1].trim() to match.groupValues[2].trim()
    }

    private fun parseAlarm(text: String): Pair<Int, Int>? {
        val match = Regex("(?:set|create) (?:an )?alarm (?:for|at) (\\d{1,2})(?::(\\d{2}))? ?(am|pm)?").find(text) ?: return null
        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        when (match.groupValues[3]) {
            "pm" -> if (hour < 12) hour += 12
            "am" -> if (hour == 12) hour = 0
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

    private fun parseAppName(text: String): String? = CommandPhrases.appName(text)

    private fun parseNavigationDestination(text: String): String? {
        CommandPhrases.navigationDestination(text)?.let { return it }
        val prefixes = listOf(
            "navigate to ",
            "directions to ",
            "give me directions to ",
            "get me directions to ",
            "show me directions to ",
            "take me to "
        )
        val prefix = prefixes.firstOrNull { text.startsWith(it) } ?: return null
        return text.removePrefix(prefix).trim().takeIf { it.isNotBlank() }
    }

    private fun containsAny(text: String, vararg options: String): Boolean {
        return options.any { text.contains(it) }
    }

    private fun normalize(text: String): String {
        return text.lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9_\\-\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractProjectName(normalized: String): String? {
        val named = Regex("(called|named)\\s+([a-z0-9_\\-]+)").find(normalized)?.groupValues?.getOrNull(2)
        if (!named.isNullOrBlank()) return sanitizeProjectName(named)
        return null
    }

    private fun sanitizeProjectName(name: String): String {
        return name.lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9_\\-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifBlank { "assistant_demo" }
    }

    private fun extractShellCommand(rawInput: String): String {
        val normalized = normalize(rawInput)
        return when {
            normalized.contains(".rish") -> rawInput.trim()
            normalized.contains("run shell") -> rawInput.substringAfter("run shell", "").trim().ifBlank { "id" }
            normalized.contains("execute shell") -> rawInput.substringAfter("execute shell", "").trim().ifBlank { "id" }
            normalized.contains("run command") -> rawInput.substringAfter("run command", "").trim().ifBlank { "id" }
            else -> "id"
        }
    }

    private fun looksLikeYes(normalized: String): Boolean {
        return normalized in setOf("yes", "confirm", "confirm reboot", "confirm command", "do it", "sure")
    }
}
