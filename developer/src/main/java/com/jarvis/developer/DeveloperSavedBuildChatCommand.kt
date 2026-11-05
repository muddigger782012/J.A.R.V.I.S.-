package com.jarvis.developer

import java.util.Locale

/** Owner-initiated commands only; never infer save or read intent from ordinary chat. */
internal object DeveloperSavedBuildChatCommand {
    enum class Action { SAVE, READ }
    private val save = setOf("save developer task", "save current developer task")
    private val read = setOf("check saved developer build", "read saved developer build",
        "resume saved developer build")
    fun match(text: String): Action? {
        val normalized = text.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!')
        return when (normalized) {
            in save -> Action.SAVE
            in read -> Action.READ
            else -> null
        }
    }
}
