package com.jarvis.developer

import java.util.Locale

/** Read-only owner command for the exact current Developer proposal. */
internal object DeveloperDiffChatCommand {
    private val phrases = setOf(
        "show developer diff",
        "show the developer diff",
        "review developer diff",
        "review the developer diff",
        "show my developer code changes"
    )
    fun isRequest(message: String): Boolean =
        message.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!') in phrases
}
