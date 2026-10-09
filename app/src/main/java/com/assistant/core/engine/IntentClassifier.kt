package com.assistant.core.engine

class IntentClassifier {

    fun classify(input: String): String {
        val normalized = input.lowercase()
        return when {
            normalized.contains("create project") -> ActionRegistry.CREATE_PROJECT
            normalized.contains("run shell") -> ActionRegistry.RUN_SHELL
            normalized.contains("reboot") -> ActionRegistry.REBOOT_DEVICE
            normalized.contains("show status") -> ActionRegistry.SHOW_STATUS
            else -> ActionRegistry.UNKNOWN
        }
    }
}
