package com.jarvis.developer

import java.util.Locale

/** Exact user phrases only; ordinary questions about builds never initiate a read. */
internal object DeveloperBuildChatCommand {
    private val commands = setOf(
        "check developer build", "check the developer build",
        "check my developer build", "check developer ci",
        "check developer build status", "refresh developer build"
    )
    fun isRequest(message: String): Boolean =
        message.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!') in commands
}
