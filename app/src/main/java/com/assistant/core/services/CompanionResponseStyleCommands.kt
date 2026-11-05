package com.assistant.core.services

import java.util.Locale

/**
 * Explicit, local, saved reply-style requests. Conversational text is not
 * authorization for source edits, installs or other side effects.
 */
object CompanionResponseStyleCommands {
    enum class Request { FEATURE_BRIEF, FEATURE_FULL, GENERAL_BRIEF, GENERAL_BALANCED, GENERAL_DETAILED }

    fun parse(input: String): Request? {
        val text = input.lowercase(Locale.ROOT).replace('’', '\'')
            .replace(Regex("""[^a-z0-9\s'-]"""), " ")
            .replace(Regex("""\s+"""), " ").trim().trimEnd('.')
        if (text.length !in 12..350 || Regex("""\b(don't|do not|never|not to|instead of)\b""").containsMatchIn(text)) return null
        val setting = Regex("""\b(in the future|from now on|going forward|by default|always|save|remember|keep|make|prefer|use|stick to|set|when i ask)\b""").containsMatchIn(text)
        if (!setting) return null
        val short = Regex("""\b(cliff notes|cliffsnotes|short|brief|concise|summar(?:y|ies|ize|ized)|quick|executive summary)\b""").containsMatchIn(text)
        val detailed = Regex("""\b(full|detailed|complete|long|in depth|in-depth)\b""").containsMatchIn(text)
        val features = Regex("""\b(features?|releases?|changes?|changelog|updates?|what's new|new features)\b""").containsMatchIn(text)
        if (features && short) return Request.FEATURE_BRIEF
        if (features && detailed) return Request.FEATURE_FULL
        val replies = Regex("""\b(repl(?:y|ies)|responses?|answers?|conversation|speaking style|verbosity)\b""").containsMatchIn(text)
        if (replies) {
            if (short) return Request.GENERAL_BRIEF
            if (detailed) return Request.GENERAL_DETAILED
            if (Regex("""\b(balanced|normal|regular)\b""").containsMatchIn(text)) return Request.GENERAL_BALANCED
        }
        return null
    }
}
