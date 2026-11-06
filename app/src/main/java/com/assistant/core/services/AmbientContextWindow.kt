package com.assistant.core.services

import java.util.ArrayDeque

/**
 * Bounded, volatile context for passive Live Chat speech. This is neither a
 * permanent transcript nor an AI memory, permission receipt or action input.
 * There are no disk writes and no network calls.
 */
class AmbientContextWindow(private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Observation(val whenMillis: Long, val words: String)
    private val recent = ArrayDeque<Observation>()

    fun clear() = recent.clear()

    fun observe(transcript: String, enabled: Boolean) {
        if (!enabled) { clear(); return }
        val now = nowMillis()
        prune(now)
        val normalized = transcript.trim().replace(Regex("""\\s+"""), " ").take(MAX_OBSERVATION_CHARS)
        if (normalized.length < 8 || !normalized.any(Char::isLetter)) return
        // Never interpret a passive utterance as a command or an authorization.
        if (LiveChatCommands.parse(normalized) != LiveChatCommands.Control.NONE) return
        recent.addLast(Observation(now, normalized))
        while (recent.size > MAX_OBSERVATIONS) recent.removeFirst()
    }

    /** A recall request is answered locally, never passed to the AI/action router. */
    fun recall(question: String, enabled: Boolean): String? {
        val input = question.trim().lowercase()
            .replace(Regex("""^(?:(?:hey|okay|ok)\\s+)?jarvis\\b\\s*[,!?;:]?\\s*"""), "")
            .trim()
        if (question.length > 180 || !RECALL_QUESTIONS.matches(input)) return null
        if (!enabled) { clear(); return "Ambient conversation context is turned off in Settings." }
        prune(nowMillis())
        if (recent.isEmpty()) return "I haven't retained any nearby conversation from the last minute and a half."
        val heard = recent.toList().takeLast(3).joinToString(" Then: ") { it.words }
        return "I recently heard: $heard. I can't reliably tell who said each part."
    }

    private fun prune(now: Long) {
        while (recent.isNotEmpty()) {
            val oldest = recent.first()
            if (now >= oldest.whenMillis && now - oldest.whenMillis < MAX_AGE_MILLIS) break
            recent.removeFirst()
        }
    }

    companion object {
        const val MAX_OBSERVATIONS = 4
        const val MAX_OBSERVATION_CHARS = 180
        const val MAX_AGE_MILLIS = 90_000L
        private val RECALL_QUESTIONS = Regex(
            """(?:what (?:were|was) (?:we|they) (?:just )?(?:talking|discussing) about|what did (?:you (?:just )?hear|they (?:just )?say|someone (?:just )?say)|catch me up(?: on (?:that|the conversation))?|remind me what (?:we|they) were (?:talking|discussing) about)[?.!]*""",
            RegexOption.IGNORE_CASE
        )
    }
}
