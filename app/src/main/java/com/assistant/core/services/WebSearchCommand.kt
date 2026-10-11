package com.assistant.core.services

/** Explicit user-requested web searches, without pretending to fetch results in-app. */
object WebSearchCommand {
    private val search = Regex(
        """^(?:please\s+)?(?:search\s+(?:the\s+)?web\s+for|search\s+(?:online\s+)?for|google|look\s+up\s+(?:online\s+)?)(?:\s+)(.+)$""",
        RegexOption.IGNORE_CASE
    )

    fun query(input: String): String? {
        val candidate = search.matchEntire(input.trim())?.groupValues?.get(1)
            ?.trim()?.trimEnd('.', '?', '!')?.trim() ?: return null
        return candidate.takeIf { it.length in 2..300 }
    }
}
