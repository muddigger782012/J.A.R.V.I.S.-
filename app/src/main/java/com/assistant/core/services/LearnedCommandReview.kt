package com.assistant.core.services

import com.assistant.core.learning.ValidatedCommandMemory
import java.util.Locale

/** Explicit local inspection/deletion commands take priority over learned aliases. */
object LearnedCommandReviewCommands {
    private val listPattern = Regex("(?i)^(?:show|list|review) (?:my )?learned commands(?: page ([0-9]+))?$")
    fun page(text: String): Int? {
        val cleaned = text.trim().trimEnd('.', '?', '!').trim().replace(Regex("\\s+"), " ")
        if (cleaned.lowercase(Locale.ROOT) in setOf("learned commands", "what commands have you learned")) return 1
        val match = listPattern.matchEntire(cleaned) ?: return null
        return if (match.groupValues[1].isEmpty()) 1 else match.groupValues[1].toIntOrNull() ?: 0
    }
    fun forgetPhrase(text: String): String? {
        val match = Regex("(?is)^(?:please )?(?:forget|remove) learned command\\s+(.+)$").matchEntire(text.trim().replace(Regex("\\s+"), " ")) ?: return null
        val phrase = match.groupValues[1].trim().trimEnd('.', '?', '!').trim()
        val pairs = listOf('"' to '"', '“' to '”', '\'' to '\'')
        return pairs.firstOrNull { phrase.length >= 2 && phrase.first() == it.first && phrase.last() == it.second }
            ?.let { phrase.substring(1, phrase.length - 1).trim() } ?: phrase
    }
    fun isReserved(text: String): Boolean = page(text) != null || forgetPhrase(text) != null
}

object LearnedCommandReview {
    const val PAGE_SIZE = 12
    private val capabilities = LearnableDeviceAction.capabilities + ContextualAction.capabilities + LearnablePreference.capabilities +
        setOf("volume_up", "volume_down", "app_update")

    fun render(page: Int, commands: ValidatedCommandMemory, memory: AssistantMemory, examples: List<IntentLearningStore.SavedExample>): String {
        if (page < 1) return "Choose a learned-command page starting at 1. Say 'show learned commands'."
        val saved = commands.snapshot().asReversed()
        val intents = examples.asReversed()
        val total = saved.size + intents.size
        if (total == 0) return "No saved command phrases or intent examples were found on this device. Successful learning will appear here."
        val pages = (total + PAGE_SIZE - 1) / PAGE_SIZE
        if (page > pages) return "There are $pages learned-command pages. Say 'show learned commands page $pages' to view the last page."
        val first = (page - 1) * PAGE_SIZE
        val lines = (first until minOf(first + PAGE_SIZE, total)).map { index ->
            if (index < saved.size) {
                val entry = saved[index]
                val available = commands.match(entry.phrase, capabilities)
                val description = if (entry.failures > 0) "paused after ${entry.failures} reported failure(s); requires a verified successful lesson"
                else if (LearnedCommandReviewCommands.isReserved(entry.phrase)) "unavailable: reserved learning control"
                else if (available == null || available.capabilityId != entry.capabilityId || available.parameters != entry.parameters)
                    "unavailable: conflicting or unsupported saved meaning"
                else describe(entry, memory)
                "${index + 1}. \"${entry.phrase}\" → $description"
            } else {
                val example = intents[index - saved.size]
                val meaning = if (LearnedCommandReviewCommands.isReserved(example.phrase)) "unavailable: reserved learning control"
                    else if (example.intent == "GENERAL_QUESTION" && example.source == "verified_local_time") "request the current time"
                    else "intent example: ${example.intent}"
                "${index + 1}. \"${example.phrase}\" → $meaning" + if (example.failures > 0) " (reported failures: ${example.failures})" else ""
            }
        }
        return "Saved learning on this device: ${saved.size} command entries, ${intents.size} intent examples. Page $page of $pages.\n" +
            lines.joinToString("\n") + "\nTo remove a phrase: forget learned command followed by its exact phrase." +
            if (page < pages) "\nMore: show learned commands page ${page + 1}." else ""
    }

    private fun describe(entry: ValidatedCommandMemory.SavedPhrase, memory: AssistantMemory): String = when (entry.capabilityId) {
        "volume_up" -> if (entry.parameters.isEmpty()) "raise media volume one step" else "unavailable: invalid volume parameters"
        "volume_down" -> if (entry.parameters.isEmpty()) "lower media volume one step" else "unavailable: invalid volume parameters"
        "app_update" -> if (entry.parameters.isEmpty()) "open the J.A.R.V.I.S. updater" else "unavailable: invalid updater parameters"
        "timer", "open_app" -> LearnableDeviceAction(entry.capabilityId, entry.parameters).takeIf { it.isValid() }?.description()
            ?: "unavailable: invalid saved action"
        "set_preference" -> LearnablePreference.fromParameters(entry.parameters)?.let { "set J.A.R.V.I.S. preferred ${it.slot} to ${it.action.parameters["label"]}; asks approval" }
            ?: "unavailable: invalid saved preference"
        "open_preferred", "repeat_action" -> {
            val reference = ContextualAction(entry.capabilityId, entry.parameters)
            if (!reference.isValid()) "unavailable: invalid saved reference"
            else {
                val label = if (entry.capabilityId == "open_preferred") "use current preferred ${entry.parameters["slot"]}"
                    else "repeat last confirmed ${entry.parameters["kind"]}"
                val action = reference.resolve(memory)
                if (action == null) "$label; unavailable until its assistant memory is saved"
                else "$label: ${action.description()}"
            }
        }
        else -> "unavailable: unsupported command"
    }
}
