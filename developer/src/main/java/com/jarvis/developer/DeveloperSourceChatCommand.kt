package com.jarvis.developer

import java.util.Locale

/**
 * A deliberately narrow read-only chat command. General coding requests still
 * use the existing proposal engine; consequential compound commands are never
 * silently reduced to a source-inspection action.
 */
internal object DeveloperSourceChatCommand {
    private val inspection = Regex(
        """^(?:inspect|analyze|scan) (?:the )?(?:(?:jarvis )?developer )?(?:repository|codebase|source tree) (?:for|about|regarding) .{8,3000}$"""
    )
    private val consequential = Regex(
        """\b(?:and|then|also) (?:merge|publish|release|install|commit|push|approve|delete)\b"""
    )

    fun isRequest(message: String): Boolean {
        val normalized = message.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        return normalized.length in 25..4000 &&
            inspection.matches(normalized) &&
            !consequential.containsMatchIn(normalized)
    }
}
