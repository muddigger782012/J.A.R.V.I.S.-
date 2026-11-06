package com.assistant.core.services

/**
 * Some Android recognition providers return no final hypothesis after already
 * emitting usable partial speech. Live Chat may recover the last partial instead
 * of silently discarding the user's entire utterance.
 *
 * Only live conversations enable this; short push-to-talk commands remain strict.
 */
internal object LiveRecognitionRecovery {
    // A partial result is not an authoritative final command. Never treat
    // provisional audio as consent for modifying apps, data or finances.
    private val privilegedWords = Regex(
        """\b(?:approve|authorize|confirm|grant|allow|install|uninstall|delete|erase|wipe|buy|purchase|pay|transfer|send|publish|merge|commit|deploy|execute|sign|reset|stop)\b""",
        RegexOption.IGNORE_CASE
    )
    fun usablePartial(speechStarted: Boolean, lastPartial: String): String? {
        if (!speechStarted) return null
        val text = lastPartial.trim()
        if (text.length !in 12..4_000) return null
        if (privilegedWords.containsMatchIn(text)) return null
        if (text.split(Regex("\\s+")).count { token -> token.any(Char::isLetter) } < 3) return null
        return text
    }
}
