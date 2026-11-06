package com.assistant.core.services

import java.util.ArrayDeque

/**
 * Volatile context ONLY for ambient utterances that Jarvis actually answered.
 * Ordinary observed room speech is never stored here. Nothing is persisted.
 */
class AmbientDialogueWindow(private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private data class Turn(val timestamp: Long, val user: String, val assistant: String)
    private val turns = ArrayDeque<Turn>()

    @Synchronized fun record(userSpeech: String, assistantReply: String) {
        val user = userSpeech.trim().take(250)
        val assistant = assistantReply.trim().take(650)
        if (user.isEmpty() || assistant.isEmpty()) return
        val now = elapsedMillis()
        prune(now)
        turns.addLast(Turn(now, user, assistant))
        while (turns.size > 3) turns.removeFirst()
    }

    @Synchronized fun snapshot(): String {
        prune(elapsedMillis())
        return turns.joinToString("\n\n") {
            "Ambient utterance: " + it.user + "\nJ.A.R.V.I.S. reply: " + it.assistant
        }
    }

    @Synchronized fun clear() = turns.clear()

    private fun prune(now: Long) {
        while (turns.isNotEmpty() &&
            (now < turns.first.timestamp || now - turns.first.timestamp > 90_000L)) {
            turns.removeFirst()
        }
    }
}
