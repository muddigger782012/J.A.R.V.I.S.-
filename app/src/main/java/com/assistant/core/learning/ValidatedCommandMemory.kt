package com.assistant.core.learning

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import com.assistant.core.services.ConfirmationWords
import com.assistant.core.services.DeviceLearningSession

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

    fun match(utterance: String, allowedCapabilityIds: Set<String>): Match? = synchronized(lock) {
        val key = normalize(utterance)
        // Older builds could incorrectly retain the approval instead of its request.
        if (key.isBlank() || ConfirmationWords.isConfirmation(utterance) || DeviceLearningSession.isOutcome(utterance)) return@synchronized null
        val entries = readEntries()
        val found = (0 until entries.length()).mapNotNull { i ->
            entries.optJSONObject(i)?.takeIf {
                normalize(it.optString("phrase")) == key &&
                    it.optString("capabilityId") in allowedCapabilityIds
            }
        }.maxByOrNull { it.optInt("successes", 0) }
        found?.let {
            val params = it.optJSONObject("parameters")
            Match(it.getString("capabilityId"), 1.0, params?.keys()?.asSequence()?.associateWith { key -> params.getString(key) } ?: emptyMap())
        }
    }

    /** Call only after the action succeeds and learning consent is enabled. */
    fun recordSuccess(utterance: String, capabilityId: String, allowedCapabilityIds: Set<String>, parameters: Map<String, String> = emptyMap()): Boolean =
        synchronized(lock) {
            val phrase = normalize(utterance)
            if (phrase.isBlank() || phrase.length > 240 || ConfirmationWords.isConfirmation(utterance) || DeviceLearningSession.isOutcome(utterance) || capabilityId !in allowedCapabilityIds) return@synchronized false
            if (parameters.size > 12 || parameters.any { it.key.length > 64 || it.value.length > 256 }) return@synchronized false
            val existing = readEntries()
            val output = JSONArray()
            var successes = 1
            for (i in 0 until existing.length()) {
                val item = existing.optJSONObject(i) ?: continue
                if (normalize(item.optString("phrase")) == phrase) {
                    if (item.optString("capabilityId") != capabilityId) return@synchronized false
                    val previous = item.optJSONObject("parameters")
                    val previousMap = previous?.keys()?.asSequence()?.associateWith { previous.getString(it) } ?: emptyMap()
                    if (previousMap != parameters) return@synchronized false
                    successes = item.optInt("successes", 1) + 1
                } else output.put(item)
            }
            output.put(JSONObject().put("phrase", phrase).put("capabilityId", capabilityId).put("parameters", JSONObject(parameters)).put("successes", successes))
            while (output.length() > maxEntries) output.remove(0)
            write(output.toString())
        }

    fun forget(utterance: String): Boolean = synchronized(lock) {
        val phrase = normalize(utterance)
        if (phrase.isBlank()) return@synchronized false
        val entries = readEntries()
        val remaining = JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            if (normalize(entry.optString("phrase")) != phrase) remaining.put(entry)
        }
        if (remaining.length() == entries.length()) return@synchronized false
        write(remaining.toString())
    }

    fun learnedPhraseCount(): Int = synchronized(lock) { readEntries().length() }

    fun latestParameterized(capabilityId: String): Match? = synchronized(lock) {
        val entries = readEntries()
        (entries.length() - 1 downTo 0).firstNotNullOfOrNull { index ->
            val item = entries.optJSONObject(index) ?: return@firstNotNullOfOrNull null
            if (item.optString("capabilityId") != capabilityId) return@firstNotNullOfOrNull null
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
