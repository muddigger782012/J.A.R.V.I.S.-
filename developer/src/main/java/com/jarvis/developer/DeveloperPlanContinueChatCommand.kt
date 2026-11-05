package com.jarvis.developer

import java.util.Locale

/** Exact owner-initiated plan continuation, never inferred from a provider's response. */
internal object DeveloperPlanContinueChatCommand {
    private val commands = setOf(
        "continue developer plan", "continue my developer plan",
        "continue development plan", "next developer task",
        "prepare next developer step"
    )
    fun isRequest(message: String): Boolean =
        message.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!') in commands
}
