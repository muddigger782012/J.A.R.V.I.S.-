package com.assistant.core.services

import com.assistant.core.engine.ActionRegistry
import com.assistant.core.engine.AssistantEngine
import com.assistant.core.models.ActionResult
import com.assistant.core.models.CapabilityState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HybridAssistantReply(
    val text: String,
    val actionResult: ActionResult? = null
)

private enum class PendingPrompt {
    NONE,
    PROJECT_NAME,
    REBOOT_CONFIRMATION
}

class HybridAssistantService(
    private val assistantEngine: AssistantEngine,
    private val actionRegistry: ActionRegistry,
    private val systemService: SystemService,
    private val capabilityProvider: () -> CapabilityState
) {

    private var pendingPrompt: PendingPrompt = PendingPrompt.NONE
    private val history = ArrayDeque<Pair<String, String>>()

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

        if (containsAny(normalized, "hello", "hi jarvis", "hey jarvis", "good morning", "good evening")) {
            val response = "Hello. I'm ready to help with device actions, status checks, Shizuku terminal commands, and project generation."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        if (containsAny(normalized, "what can you do", "help", "capabilities", "features")) {
            val capabilities = capabilityProvider()
            val response = buildString {
                appendLine("I can help with:")
                appendLine("- Create local coding projects")
                appendLine("- Run Shizuku shell commands")
                appendLine("- Show system/capability status")
                appendLine("- Manage Dhizuku/Device Owner controls")
                appendLine("- Voice and terminal workflows")
                appendLine()
                appendLine("Current capability flags:")
                appendLine("shizuku=${capabilities.shizuku}, dhizuku=${capabilities.dhizuku}, deviceOwner=${capabilities.deviceOwner}")
            }
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        if (containsAny(normalized, "time", "date")) {
            val now = SimpleDateFormat("EEE, MMM d • HH:mm", Locale.getDefault()).format(Date())
            val response = "Current local time is $now."
            remember(userInput, response)
            return HybridAssistantReply(response)
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
            val result = assistantEngine.executeAction(actionRegistry.runShellRequest(command, confirmed = true))
            return replyFromResult(
                userText = userInput,
                preface = "Running shell command via Shizuku.",
                result = result
            )
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

        val fallback = assistantEngine.handleUserCommand(userInput)
        if (!fallback.success && fallback.adapterUsed == "ENGINE") {
            val response = buildString {
                appendLine("I couldn't map that request yet.")
                appendLine("Try examples:")
                appendLine("- create project named demo")
                appendLine("- run shell id")
                append("- show status")
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

    private fun remember(userText: String, assistantText: String) {
        history.addLast(userText to assistantText)
        while (history.size > 30) {
            history.removeFirst()
        }
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
        return containsAny(normalized, "yes", "confirm", "confirm reboot", "do it", "sure")
    }
}
