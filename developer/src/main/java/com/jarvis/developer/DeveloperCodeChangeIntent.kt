package com.jarvis.developer

import java.util.Locale

/**
 * A narrowly recognized coding imperative switches to proposal-only mode.
 * Questions, status reads, ambiguous app-update requests and Personal Jarvis
 * work remain conversational. No pattern can authorize a GitHub write.
 */
internal object DeveloperCodeChangeIntent {
    private val imperative = Regex("^(?:implement|fix|repair|modify|refactor|improve|change|add|build|create)\\b")
    private val developerObject = Regex(
        "(?:\\b(?:jarvis|j\\.a\\.r\\.v\\.i\\.s\\.) developer\\b|" +
        "\\bdeveloper (?:app|code|source|project|module|tests?|voice|network|chat|ui|workflow|build system)\\b|" +
        "\\bin (?:the )?developer\\b)"
    )
    fun isRequest(text: String): Boolean {
        if (text.length !in 12..12_000) return false
        val request = text.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        return imperative.containsMatchIn(request) && developerObject.containsMatchIn(request)
    }
}
