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


/**
 * Explicit Personal Assistant development requests enter the same proposal-only
 * engine as Developer, but with the existing, stricter PERSONAL source allowlist.
 * A product mention or general discussion never grants commit authority.
 */
internal object PersonalCodeChangeIntent {
    private val imperative = Regex("^(?:implement|fix|repair|modify|refactor|improve|change|add|build|create)\\b")
    private val personalObject = Regex(
        "(?:\\bpersonal (?:j\\.?a\\.?r\\.?v\\.?i\\.?s\\.?|jarvis|assistant|app)\\b|" +
        "\\bmy (?:personal )?(?:j\\.?a\\.?r\\.?v\\.?i\\.?s\\.?|jarvis|assistant)\\b|" +
        "\\bin (?:the |my )?personal app\\b)"
    )
    fun isRequest(text: String): Boolean {
        if (text.length !in 12..12_000) return false
        val request = text.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        return imperative.containsMatchIn(request) && personalObject.containsMatchIn(request)
    }
}

/** Brainstorming is conversational: a speculative question is not an approved scope. */
internal object DevelopmentExplorationIntent {
    fun isRequest(text: String): Boolean {
        val request = text.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        if (request.length !in 8..12_000) return false
        return request.matches(Regex("^(?:what|which|how|can you suggest|suggest|recommend|brainstorm)\\b.*")) &&
            Regex("\\b(?:features?|ideas?|suggest|propose|recommend|improvements?|possibilities)\\b")
                .containsMatchIn(request)
    }
}
