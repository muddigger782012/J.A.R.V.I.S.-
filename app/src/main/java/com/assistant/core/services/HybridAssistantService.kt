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

        if (isTimeRequest(normalized)) {
            val response = "It's " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()) + "."
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
        if (normalized in setOf("volume up", "turn volume up", "increase volume")) {
            val ok = androidAssistant.adjustVolume(android.media.AudioManager.ADJUST_RAISE)
            val response = if (ok) "Volume increased." else "I couldn't change the volume."
            remember(userInput, response); return HybridAssistantReply(response)
        }
        if (normalized in setOf("volume down", "turn volume down", "decrease volume")) {
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

        learningStore.classify(normalized)?.let { learned ->
            val response = "I recognized this as ${learned.intent} from local learning, but that learned intent is not connected to an executable capability yet."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

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
                val response = directOnlineClient.gemini(rawInput)
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
        if (!FallbackLessonProtocol.accepts(lesson) || !lesson.canTeachLocally()) return false
        learningStore.learn(
            utterance = userText,
            intent = lesson.intent,
            entities = lesson.entities,
            source = "validated_fallback_v${FallbackLessonProtocol.VERSION}"
        )
        return true
    }

    @Synchronized
    fun getConversationSnapshot(limit: Int = 8): String {
        return history.takeLast(limit).joinToString(separator = "\n\n") { (user, assistant) ->
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
