package com.jarvis.developer

import java.util.Locale

/** Explicit chat commands only; never infer approval from a provider response. */
internal object DeveloperPlanCompletionChatCommand {
    enum class Action { REVIEW, CONFIRM }
    fun match(message: String): Action? = when (
        message.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!')
    ) {
        "review developer completion", "review developer build completion" -> Action.REVIEW
        "confirm developer completion review" -> Action.CONFIRM
        else -> null
    }
}
