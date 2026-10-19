package com.assistant.core.engine

import java.util.Locale

class IntentClassifier {

    fun classify(input: String): String {
        // Mentioning a command in a question, correction or example is not a request to run it.
        val normalized = input.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!').trim()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("^(?:(?:hey )?jarvis[, :]*)"), "")
            .replace(Regex("^(?:(?:can|could|would) you |please )"), "")
            .removeSuffix(" please").trim()
        return when {
            Regex("^(?:create|make|generate)(?: a| an)? (?:new )?project(?: (?:called|named) [a-z0-9_-]+)?$").matches(normalized) ||
                normalized == "new project" -> ActionRegistry.CREATE_PROJECT
            Regex("^(?:run shell|execute shell|run command)(?: .+)?$").matches(normalized) -> ActionRegistry.RUN_SHELL
            Regex("^(?:reboot|restart)(?: (?:my |the |this )?(?:device|phone))?(?: now)?$").matches(normalized) -> ActionRegistry.REBOOT_DEVICE
            normalized in setOf("status", "system status", "show status", "show system status", "show my system status",
                "what is your status", "what's your status", "what is the system status") -> ActionRegistry.SHOW_STATUS
            else -> ActionRegistry.UNKNOWN
        }
    }
}
