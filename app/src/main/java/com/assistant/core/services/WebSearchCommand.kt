package com.assistant.core.services

/** Explicit user-requested web searches, without pretending to fetch results in-app. */
object WebSearchCommand {
    fun videoQuery(input: String): String? {
        val direct = Regex(
        """^(?:please\s+)?(?:can\s+you\s+|could\s+you\s+)?(?:show|find|search(?:\s+the\s+web)?\s+for|get)\s+(?:me\s+)?(?:some\s+|a\s+)?(?:videos?|clips?)\s+(?:of|about|on|for)\s+(.+)$""",
        RegexOption.IGNORE_CASE
        ).matchEntire(input.trim())?.groupValues?.get(1)
        val suffix = Regex("""^(?:please\s+)?search\s+(?:the\s+)?web\s+for\s+(.+?)\s+videos?$""", RegexOption.IGNORE_CASE)
            .matchEntire(input.trim())?.groupValues?.get(1)
        return (direct ?: suffix)?.trim()?.trimEnd('.', '?', '!')?.trim()?.takeIf { it.length in 2..300 }
    }

    private val search = Regex(
        """^(?:please\s+)?(?:search\s+(?:the\s+)?web\s+for|search\s+(?:online\s+)?for|google|look\s+up(?:\s+online)?|find\s+(?:me\s+)?(?:information|info)\s+(?:about|on))(?:\s+)(.+)$""",
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

    /** Detect picture requests phrased as ordinary assistant commands. */
    fun imageQueryNatural(input: String): String? {
        val direct = imageQuery(input)
        if (direct != null) return direct
        val pattern = Regex(
            """^(?:please\s+)?(?:can\s+you\s+|could\s+you\s+)?(?:show|find|get)\s+(?:me\s+)?(?:some\s+|a\s+|an\s+)?(?:pictures|photos|images|picture|photo|image)\s+(?:of|for)\s+(?:a\s+|an\s+|the\s+)?(.+)$""",
            RegexOption.IGNORE_CASE
        )
        return pattern.matchEntire(input.trim())?.groupValues?.get(1)
            ?.trim()?.trimEnd('.', '?', '!')?.trim()
            ?.takeIf { it.length in 2..300 }
    }

    /** Public catalogue availability questions need retrieved sources, not a static AI answer. */
    fun currentInfoQuery(input: String): String? {
        val text = input.trim().trimEnd('.', '?', '!').trim()
        if (text.length !in 2..300 || !Regex("(?i)^(?:is|are|has|when)\\b").containsMatchIn(text)) return null
        if (!Regex("(?i)\\b(?:season|episode|movie|series|streaming)\\b").containsMatchIn(text)) return null
        return text.takeIf { Regex("(?i)\\b(?:available|released|premiere|streaming)\\b").containsMatchIn(it) &&
            Regex("(?i)\\b(?:yet|now|currently|today|amazon|prime|netflix|hulu|disney|peacock|paramount)\\b").containsMatchIn(it) }
    }

    fun query(input: String): String? {
        val candidate = search.matchEntire(input.trim())?.groupValues?.get(1)
            ?.trim()?.trimEnd('.', '?', '!')?.trim() ?: downloadQuery(input) ?: return null
        return candidate.takeIf { it.length in 2..300 }
    }

    /** Finding a public download page is retrieval, not permission to install anything. */
    fun downloadQuery(input: String): String? {
        val match = Regex(
            """^(?:please\s+)?(?:can\s+you\s+|could\s+you\s+)?(?:find|show|get|locate)\s+(?:me\s+)?(?:(?:a|an|some|the)\s+)?(downloadable\s+.+|downloads?\s+(?:for|of)\s+.+|download\s+(?:links?|pages?)\s+(?:for|to)\s+.+|links?\s+(?:to|for)\s+download\s+.+)$""",
            RegexOption.IGNORE_CASE
        ).matchEntire(input.trim()) ?: return null
        return match.groupValues[1].trim().trimEnd('.', '?', '!').trim().takeIf { it.length in 2..300 }
    }

    /** A bounded next-turn retry uses only the immediately preceding explicit search request. */
    fun isDownloadSearchFollowup(input: String): Boolean = Regex(
        """^(?:(?:can(?:not|'t)?|could)\s+you|you\s+(?:can(?:not|'t)?))\s+(?:search\s+for|find|look\s+up)\s+(?:downloadable\s+links?|download\s+links?)(?:\s+(?:available\s+)?online)?[?!\.]*$""",
        RegexOption.IGNORE_CASE
    ).matches(input.trim())

    fun downloadFollowupQuery(input: String, previousInput: String?): String? {
        if (!isDownloadSearchFollowup(input)) return null
        return previousInput?.let(::query)
    }
}
