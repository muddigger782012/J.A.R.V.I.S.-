package com.jarvis.developer

import java.util.Locale

/**
 * Explicit owner intent only; ordinary build discussion is never interpreted
 * as permission to run a provider, prepare code, or modify the repository.
 */
internal object DeveloperRepairChatCommand {
    private val commands = setOf(
        "prepare developer repair",
        "prepare a developer repair",
        "repair the failed developer build",
        "repair failed developer build",
        "prepare repair from failed developer build"
    )
    fun isRequest(message: String): Boolean =
        message.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!') in commands
}
