package com.assistant.core.learning

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Persistent, opt-in command phrase learning. This class never executes a command:
 * callers must resolve a returned capability ID through an explicit allowlist and
 * verify that the action actually succeeded before calling recordSuccess.
 */
class ValidatedCommandMemory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_validated_commands", Context.MODE_PRIVATE)
    private val lock = Any()
    private val maxEntries = 500

    data class Match(val capabilityId: String, val confidence: Double)

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")

    fun match(utterance: String, allowedCapabilityIds: Set<String>): Match? = synchronized(lock) {
        val key = normalize(utterance)
        if (key.isBlank()) return@synchronized null
        val entries = readEntries()
        val found = (0 until entries.length()).mapNotNull { i ->
            entries.optJSONObject(i)?.takeIf {
                it.optString("phrase") == key &&
                    it.optString("capabilityId") in allowedCapabilityIds
            }
        }.maxByOrNull { it.optInt("successes", 0) }
        found?.let { Match(it.getString("capabilityId"), 1.0) }
    }

    /** Call only after the action succeeds and learning consent is enabled. */
    fun recordSuccess(utterance: String, capabilityId: String, allowedCapabilityIds: Set<String>): Boolean =
        synchronized(lock) {
            val phrase = normalize(utterance)
            if (phrase.isBlank() || phrase.length > 240 || capabilityId !in allowedCapabilityIds) return@synchronized false
            val existing = readEntries()
            val output = JSONArray()
            var successes = 1
            for (i in 0 until existing.length()) {
                val item = existing.optJSONObject(i) ?: continue
                if (item.optString("phrase") == phrase) {
                    if (item.optString("capabilityId") != capabilityId) return@synchronized false
                    successes = item.optInt("successes", 1) + 1
                } else output.put(item)
            }
            output.put(JSONObject().put("phrase", phrase).put("capabilityId", capabilityId).put("successes", successes))
            while (output.length() > maxEntries) output.remove(0)
            prefs.edit().putString("entries", output.toString()).commit()
        }

    fun learnedPhraseCount(): Int = synchronized(lock) { readEntries().length() }

    fun clear() = synchronized(lock) { prefs.edit().remove("entries").apply() }

    private fun readEntries(): JSONArray = try {
        JSONArray(prefs.getString("entries", "[]") ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }
}
