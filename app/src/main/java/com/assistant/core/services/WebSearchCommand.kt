package com.assistant.core.services

/** Explicit user-requested web searches, without pretending to fetch results in-app. */
object WebSearchCommand {
    private val search = Regex(
        """^(?:please\s+)?(?:search\s+(?:the\s+)?web\s+for|search\s+(?:online\s+)?for|google|look\s+up(?:\s+online)?)(?:\s+)(.+)$""",
        RegexOption.IGNORE_CASE
    )

    fun imageQuery(input: String): String? {
        val trimmed = input.trim()
        val direct = Regex(
            """^(?:please\s+)?(?:show\s+me\s+(?:a\s+)?(?:picture|photo|image)\s+of|find\s+(?:me\s+)?(?:pictures|photos|images)\s+of)\s+(.+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(trimmed)?.groupValues?.get(1)
        val suffix = Regex(
            """^(?:please\s+)?search\s+(?:the\s+)?web\s+for\s+(.+?)\s+(?:pictures|photos|images)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(trimmed)?.groupValues?.get(1)
        return (direct ?: suffix)?.trim()?.trimEnd('.', '?', '!')?.trim()
            ?.takeIf { it.length in 2..300 }
    }

    fun query(input: String): String? {
        val candidate = search.matchEntire(input.trim())?.groupValues?.get(1)
            ?.trim()?.trimEnd('.', '?', '!')?.trim() ?: return null
        return candidate.takeIf { it.length in 2..300 }
    }
}
