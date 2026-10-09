package com.assistant.core.services

import com.assistant.core.engine.ActionRegistry
import com.assistant.core.models.ActionRequest

data class ParsedVoiceCommand(
    val actionRequest: ActionRequest? = null,
    val fallbackTextCommand: String? = null,
    val localCommand: LocalVoiceCommand = LocalVoiceCommand.NONE,
    val responseHint: String? = null
)

enum class LocalVoiceCommand {
    NONE,
    STOP_VOICE,
    START_VOICE,
    OPEN_SETTINGS
}

class VoiceCommandParser(private val actionRegistry: ActionRegistry) {

    fun parse(raw: String, config: VoiceConfig): ParsedVoiceCommand {
        val normalized = normalize(raw)
        if (normalized.isBlank()) {
            return ParsedVoiceCommand(responseHint = "I did not catch that command.")
        }

        if (containsAny(normalized, stopVoicePhrases(config))) {
            return ParsedVoiceCommand(localCommand = LocalVoiceCommand.STOP_VOICE, responseHint = "Stopping voice mode.")
        }
        if (containsAny(normalized, startVoicePhrases(config))) {
            return ParsedVoiceCommand(localCommand = LocalVoiceCommand.START_VOICE, responseHint = "Voice mode enabled.")
        }
        if (containsAny(normalized, settingsPhrases(config))) {
            return ParsedVoiceCommand(localCommand = LocalVoiceCommand.OPEN_SETTINGS, responseHint = "Opening voice settings.")
        }

        if (containsAny(normalized, statusPhrases(config))) {
            return ParsedVoiceCommand(
                actionRequest = actionRegistry.showStatusRequest(),
                responseHint = "Showing current capability status."
            )
        }

        parseProjectCommand(normalized, config)?.let { return it }
        parseShellCommand(normalized, config)?.let { return it }
        parseRebootCommand(normalized, config)?.let { return it }

        return ParsedVoiceCommand(
            fallbackTextCommand = normalized,
            responseHint = "Processing command with fallback intent classifier."
        )
    }

    private fun parseProjectCommand(normalized: String, config: VoiceConfig): ParsedVoiceCommand? {
        val createTokens = projectPhrases(config)
        if (createTokens.none { normalized.contains(it) }) return null

        val name = extractProjectName(normalized, config.defaultProjectName)
        return ParsedVoiceCommand(
            actionRequest = actionRegistry.createProjectRequest(name),
            responseHint = "Creating project $name."
        )
    }

    private fun parseShellCommand(normalized: String, config: VoiceConfig): ParsedVoiceCommand? {
        val command = extractShellCommand(normalized, shellPhrases(config)) ?: return null
        return ParsedVoiceCommand(
            actionRequest = actionRegistry.runShellRequest(command = command, confirmed = true),
            responseHint = "Running shell command."
        )
    }

    private fun parseRebootCommand(normalized: String, config: VoiceConfig): ParsedVoiceCommand? {
        val wantsReboot = containsAny(normalized, rebootPhrases(config))
        if (!wantsReboot) return null

        val confirmed = containsAny(normalized, listOf("confirm", "yes reboot", "reboot now"))
        return ParsedVoiceCommand(
            actionRequest = actionRegistry.rebootDeviceRequest(confirmed = confirmed),
            responseHint = if (confirmed) "Attempting reboot action." else "Reboot request heard. Confirmation required."
        )
    }

    private fun extractProjectName(normalized: String, defaultName: String): String {
        val pattern = Regex("(called|named)\\s+([a-z0-9_\\-]+)")
        val candidate = pattern.find(normalized)?.groupValues?.getOrNull(2)
        if (!candidate.isNullOrBlank()) return candidate
        return defaultName.trim().ifBlank { "assistant_demo" }
    }

    private fun containsAny(text: String, options: List<String>): Boolean {
        return options.any { text.contains(it) }
    }

    private fun extractShellCommand(text: String, shellTriggers: List<String>): String? {
        val fromCustomTrigger = shellTriggers.firstOrNull { text.contains(it) }?.let { trigger ->
            text.substringAfter(trigger, missingDelimiterValue = "").trim()
        }
        val direct = fromCustomTrigger?.ifBlank { "id" }
        if (!direct.isNullOrBlank()) return direct

        val shellPattern = Regex("(run|execute) (shell|command) (.+)")
        val match = shellPattern.find(text)
        return match?.groupValues?.getOrNull(3)?.trim()?.ifBlank { "id" }
    }

    private fun projectPhrases(config: VoiceConfig): List<String> = phraseList(
        config.customProjectPhrases,
        listOf("create project", "new project", "generate project", "make project")
    )

    private fun statusPhrases(config: VoiceConfig): List<String> = phraseList(
        config.customStatusPhrases,
        listOf("status", "capability", "what can you do", "show system status")
    )

    private fun shellPhrases(config: VoiceConfig): List<String> = phraseList(
        config.customShellPhrases,
        listOf("run shell", "execute command", "run command")
    )

    private fun rebootPhrases(config: VoiceConfig): List<String> = phraseList(
        config.customRebootPhrases,
        listOf("reboot", "restart device", "restart phone")
    )

    private fun settingsPhrases(config: VoiceConfig): List<String> = phraseList(
        config.customSettingsPhrases,
        listOf("open voice settings", "voice settings", "configure voice")
    )

    private fun startVoicePhrases(config: VoiceConfig): List<String> = phraseList(
        config.customStartVoicePhrases,
        listOf("start listening", "enable voice", "wake up")
    )

    private fun stopVoicePhrases(config: VoiceConfig): List<String> = phraseList(
        config.customStopVoicePhrases,
        listOf("stop listening", "stop voice", "go silent", "disable voice")
    )

    private fun phraseList(customCsv: String, defaults: List<String>): List<String> {
        val custom = customCsv.split(",")
            .map { normalize(it) }
            .filter { it.isNotBlank() }
        return (defaults + custom).distinct()
    }

    private fun normalize(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-z0-9_\\-\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
