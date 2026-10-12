package com.assistant.core.learning

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import com.assistant.core.services.ExactVolumeLearning
import com.assistant.core.services.ConfirmationWords
import com.assistant.core.services.DeviceLearningSession
import com.assistant.core.services.LearnedCommandReviewCommands

/**
 * Persistent, opt-in command phrase learning. This class never executes a command:
 * callers must resolve a returned capability ID through an explicit allowlist and
 * verify that the action actually succeeded before calling recordSuccess.
 */
class ValidatedCommandMemory internal constructor(
    private val read: () -> String,
    private val write: (String?) -> Boolean
) {
    constructor(context: Context) : this(
        read = { context.applicationContext.getSharedPreferences("jarvis_validated_commands", Context.MODE_PRIVATE).getString("entries", "[]") ?: "[]" },
        write = { value ->
            val editor = context.applicationContext.getSharedPreferences("jarvis_validated_commands", Context.MODE_PRIVATE).edit()
            if (value == null) editor.remove("entries") else editor.putString("entries", value)
            editor.commit()
        }
    )
    private val maxEntries = 500

    data class Match(val capabilityId: String, val confidence: Double, val parameters: Map<String, String> = emptyMap())

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).trim().trimEnd('.', '?', '!').trim().replace(Regex("\\s+"), " ")

    // Word hyphens are transcription/typing variants. Preserve signs and other punctuation.
    private fun matchingKey(value: String): String = normalize(value)
        .replace(Regex("(?<=\\p{L})[-\\u2010\\u2011](?=\\p{L})"), " ")

    /** Includes paused and conflicting entries so another command cannot steal their phrase. */
    fun hasSavedPhrase(utterance: String): Boolean = synchronized(lock) {
        val key = matchingKey(utterance)
        val entries = readEntries()
        (0 until entries.length()).any { matchingKey(entries.optJSONObject(it)?.optString("phrase") ?: "") == key }
    }

    fun match(utterance: String, allowedCapabilityIds: Set<String>): Match? = synchronized(lock) {
        val key = matchingKey(utterance)
        // Older builds could incorrectly retain the approval instead of its request.
        if (key.isBlank() || ConfirmationWords.isConfirmation(utterance) || DeviceLearningSession.isOutcome(utterance)) return@synchronized null
        val entries = readEntries()
        val meanings = mutableSetOf<Match>()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            if (matchingKey(entry.optString("phrase")) != key) continue
            if (entry.optInt("failures", 0) > 0) return@synchronized null
            val meaning = runCatching {
                val params = entry.optJSONObject("parameters")
                Match(entry.getString("capabilityId"), 1.0, params?.keys()?.asSequence()?.associateWith { params.getString(it) } ?: emptyMap())
            }.getOrNull() ?: return@synchronized null
            meanings.add(meaning)
        }
        // Legacy entries can collide after normalization. Never choose by frequency,
        // even if only one meaning is in this caller's capability allowlist.
        meanings.singleOrNull()?.takeIf { it.capabilityId in allowedCapabilityIds && !ExactVolumeLearning.conflictsWithRelative(utterance, it.capabilityId) }
    }

    /** Call only after the action succeeds and learning consent is enabled. */
    fun recordSuccess(utterance: String, capabilityId: String, allowedCapabilityIds: Set<String>, parameters: Map<String, String> = emptyMap()): Boolean =
        synchronized(lock) {
            val phrase = normalize(utterance)
            if (phrase.isBlank() || phrase.length > 240 || ConfirmationWords.isConfirmation(utterance) || DeviceLearningSession.isOutcome(utterance) || LearnedCommandReviewCommands.isReserved(utterance) || capabilityId !in allowedCapabilityIds) return@synchronized false
            if (ExactVolumeLearning.conflictsWithRelative(utterance, capabilityId)) return@synchronized false
            if (parameters.size > 12 || parameters.any { it.key.length > 64 || it.value.length > 256 }) return@synchronized false
            val existing = readEntries()
            val output = JSONArray()
            var successes = 1
            for (i in 0 until existing.length()) {
                val item = existing.optJSONObject(i) ?: continue
                if (matchingKey(item.optString("phrase")) == matchingKey(phrase)) {
                    if (item.optString("capabilityId") != capabilityId) return@synchronized false
                    val previous = item.optJSONObject("parameters")
                    val previousMap = previous?.keys()?.asSequence()?.associateWith { previous.getString(it) } ?: emptyMap()
                    if (previousMap != parameters) return@synchronized false
                    successes = item.optInt("successes", 1) + 1
                } else output.put(item)
            }
            output.put(JSONObject().put("phrase", phrase).put("capabilityId", capabilityId).put("parameters", JSONObject(parameters)).put("successes", successes).put("failures", 0))
            while (output.length() > maxEntries) output.remove(0)
            write(output.toString())
        }

    /** Pauses only the exact saved meaning that produced the reported result. */
    fun recordFailure(utterance: String, capabilityId: String, parameters: Map<String, String>): Boolean = synchronized(lock) {
        val key = matchingKey(utterance)
        if (key.isBlank() || ConfirmationWords.isConfirmation(utterance) || DeviceLearningSession.isOutcome(utterance)) return@synchronized false
        val entries = readEntries()
        var found = false
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            if (matchingKey(entry.optString("phrase")) != key) continue
            val stored = runCatching {
                val params = entry.optJSONObject("parameters")
                params?.keys()?.asSequence()?.associateWith { params.getString(it) } ?: emptyMap()
            }.getOrNull() ?: return@synchronized false
            if (entry.optString("capabilityId") != capabilityId || stored != parameters) return@synchronized false
            found = true
            entry.put("failures", entry.optInt("failures", 0).coerceIn(0, Int.MAX_VALUE - 1) + 1)
        }
        found && write(entries.toString())
    }

    fun isPaused(utterance: String): Boolean = synchronized(lock) {
        val key = matchingKey(utterance)
        val entries = readEntries()
        (0 until entries.length()).any { index -> entries.optJSONObject(index)?.let {
            matchingKey(it.optString("phrase")) == key && it.optInt("failures", 0) > 0
        } == true }
    }

    fun forget(utterance: String): Boolean = synchronized(lock) {
        val phrase = matchingKey(utterance)
        if (phrase.isBlank()) return@synchronized false
        val entries = readEntries()
        val remaining = JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            if (matchingKey(entry.optString("phrase")) != phrase) remaining.put(entry)
        }
        if (remaining.length() == entries.length()) return@synchronized false
        write(remaining.toString())
    }

    data class SavedPhrase(val phrase: String, val capabilityId: String, val parameters: Map<String, String>, val successes: Int, val failures: Int = 0)

    /** Read-only copies for local inspection; no targets are executed or storage rewritten. */
    fun snapshot(): List<SavedPhrase> = synchronized(lock) {
        val entries = readEntries()
        (0 until entries.length()).mapNotNull { index ->
            runCatching {
                val entry = entries.getJSONObject(index)
                val params = entry.optJSONObject("parameters")
                SavedPhrase(entry.getString("phrase"), entry.getString("capabilityId"),
                    params?.keys()?.asSequence()?.associateWith { params.getString(it) } ?: emptyMap(), entry.optInt("successes", 1), entry.optInt("failures", 0))
            }.getOrNull()
        }
    }

    fun learnedPhraseCount(): Int = synchronized(lock) { readEntries().length() }

    fun latestParameterized(capabilityId: String): Match? = synchronized(lock) {
        val entries = readEntries()
        (entries.length() - 1 downTo 0).firstNotNullOfOrNull { index ->
            val item = entries.optJSONObject(index) ?: return@firstNotNullOfOrNull null
            if (item.optString("capabilityId") != capabilityId || item.optInt("failures", 0) > 0) return@firstNotNullOfOrNull null
            runCatching {
                val params = item.getJSONObject("parameters")
                Match(capabilityId, 1.0, params.keys().asSequence().associateWith { params.getString(it) })
            }.getOrNull()
        }
    }

    fun clear(): Boolean = synchronized(lock) { write(null) }

    private fun readEntries(): JSONArray = try {
        JSONArray(read())
    } catch (_: Exception) {
        JSONArray()
    }

    companion object {
        // Foreground chat and background voice create separate memory instances.
        private val lock = Any()
    }
}
