package com.assistant.core.learning

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Local storage for user-approved routines. This registry never executes actions.
 * Callers must confirm each proposal and dispatch only allowlisted capabilities.
 */
class ApprovedRoutineRegistry(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jarvis_approved_routines", Context.MODE_PRIVATE)
    private val lock = Any()
    private val allowed = setOf("volume_up", "volume_down", "app_update")
    private val maxRoutines = 50

    data class Routine(val phrase: String, val actions: List<String>)

    private fun normalize(text: String) = text.lowercase(Locale.ROOT).trim()
        .replace(Regex("\\s+"), " ")

    fun propose(phrase: String, actions: List<String>): Routine? {
        val key = normalize(phrase)
        if (key.length !in 3..120 || actions.size !in 1..5 ||
            actions.any { it !in allowed } || actions.distinct().size != actions.size ||
            key.startsWith("teach ") || key.startsWith("confirm ") ||
            key.startsWith("delete ") || key.startsWith("reset ")) return null
        return Routine(key, actions.toList())
    }

    /** Invoke only after the user explicitly confirms the proposed routine. */
    fun saveApproved(routine: Routine): Boolean = synchronized(lock) {
        if (propose(routine.phrase, routine.actions) != routine) return@synchronized false
        val entries = read()
        val output = JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            if (entry.optString("phrase") != routine.phrase) output.put(entry)
        }
        output.put(JSONObject().put("phrase", routine.phrase)
            .put("actions", JSONArray(routine.actions)))
        while (output.length() > maxRoutines) output.remove(0)
        prefs.edit().putString("routines", output.toString()).commit()
    }

    fun find(phrase: String): Routine? = synchronized(lock) {
        val key = normalize(phrase)
        val entries = read()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            if (entry.optString("phrase") != key) continue
            val values = entry.optJSONArray("actions") ?: continue
            val actions = (0 until values.length()).map { values.optString(it) }
            return@synchronized propose(key, actions)
        }
        null
    }

    fun forget(phrase: String): Boolean = synchronized(lock) {
        val key = normalize(phrase)
        val entries = read()
        val output = JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            if (entry.optString("phrase") != key) output.put(entry)
        }
        if (output.length() == entries.length()) return@synchronized false
        prefs.edit().putString("routines", output.toString()).commit()
    }

    fun count(): Int = synchronized(lock) { read().length() }

    private fun read(): JSONArray = try {
        JSONArray(prefs.getString("routines", "[]") ?: "[]")
    } catch (_: Exception) { JSONArray() }
}
