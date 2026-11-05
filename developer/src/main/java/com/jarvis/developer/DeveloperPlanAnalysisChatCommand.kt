package com.jarvis.developer

import java.util.Locale

/** Exact, separate commands prevent approval being inferred from conversational text. */
internal object DeveloperPlanAnalysisChatCommand {
    enum class Action { REVIEW, CONFIRM }
    fun match(message: String): Action? = when (
        message.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!')
    ) {
        "review developer plan", "review my developer plan",
        "review developer analysis checkpoint" -> Action.REVIEW
        "approve developer plan analysis" -> Action.CONFIRM
        else -> null
    }
}
