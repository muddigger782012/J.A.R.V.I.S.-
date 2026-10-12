package com.assistant.core.learning

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Local storage for user-approved routines. This registry never executes actions.
 * Callers must confirm each proposal and dispatch only allowlisted capabilities.
 */
class ApprovedRoutineRegistry internal constructor(private val readValue: () -> String, private val write: (String) -> Boolean) {
    constructor(context: Context) : this(
        { context.applicationContext.getSharedPreferences("jarvis_approved_routines", Context.MODE_PRIVATE).getString("routines", "[]") ?: "[]" },
        { value -> context.applicationContext.getSharedPreferences("jarvis_approved_routines", Context.MODE_PRIVATE).edit().putString("routines", value).commit() }
    )
    private val maxRoutines = 50

    data class Routine(val phrase: String, val actions: List<String>)

    private fun normalize(text: String) = text.lowercase(Locale.ROOT).trim()
        .replace(Regex("\\s+"), " ")

    fun propose(phrase: String, actions: List<String>): Routine? {
        val key = normalize(phrase)
        if (!com.assistant.core.services.RoutineProposal.isUsableName(key) || key.length !in 3..120 || !com.assistant.core.services.RoutineSteps.isValid(actions) ||
            key.startsWith("teach ") || key.startsWith("confirm ") ||
            key.startsWith("delete ") || key.startsWith("reset ") ||
            key.startsWith("forget ") || key.startsWith("cancel ") ||
            key.startsWith("list ") || key.startsWith("show ") ||
            key.startsWith("call ") || key.startsWith("text ") ||
            key.startsWith("send ") || key.startsWith("pay ") ||
            key.startsWith("purchase ") || key.startsWith("reboot ") ||
            key in setOf("routine status", "update app", "update jarvis", "upgrade app",
                "upgrade jarvis", "check for jarvis update", "volume up", "volume down",
                "learning status", "clear local learning")) return null
        return Routine(key, actions.toList())
    }

    /** Invoke only after the user explicitly confirms the proposed routine. */
    fun saveApproved(routine: Routine): Boolean = synchronized(lock) {
        if (propose(routine.phrase, routine.actions) != routine) return@synchronized false
        val entries = read()
        val existing = (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }
            .filter { normalize(it.optString("phrase")) == routine.phrase }
        if (existing.isNotEmpty()) return@synchronized existing.all { entry ->
            val steps = entry.optJSONArray("actions") ?: return@all false
            (0 until steps.length()).map { steps.optString(it) } == routine.actions
        }
        if (entries.length() >= maxRoutines) return@synchronized false
        val output = JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            if (entry.optString("phrase") != routine.phrase) output.put(entry)
        }
        output.put(JSONObject().put("phrase", routine.phrase)
            .put("actions", JSONArray(routine.actions)))
        runCatching { write(output.toString()) }.getOrDefault(false)
    }

    fun find(phrase: String): Routine? = synchronized(lock) {
        val key = normalize(phrase)
        val entries = read()
        val matches = mutableListOf<Routine>()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            if (normalize(entry.optString("phrase")) != key) continue
            val values = entry.optJSONArray("actions") ?: return@synchronized null
            val actions = (0 until values.length()).map { values.optString(it) }
            matches.add(propose(key, actions) ?: return@synchronized null)
        }
        matches.distinct().singleOrNull()
    }

    fun forget(phrase: String): Boolean = synchronized(lock) {
        val key = normalize(phrase)
        val entries = read()
        val output = JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            if (normalize(entry.optString("phrase")) != key) output.put(entry)
        }
        if (output.length() == entries.length()) return@synchronized false
        runCatching { write(output.toString()) }.getOrDefault(false)
    }

    fun count(): Int = synchronized(lock) { read().length() }

    /** A short preview suitable for a local status response; never executes routines. */
    fun listSummaries(limit: Int = 10): List<String> = synchronized(lock) {
        val entries = read()
        val summaries = mutableListOf<String>()
        for (i in 0 until entries.length()) {
            if (summaries.size >= limit.coerceIn(0, 50)) break
            val entry = entries.optJSONObject(i) ?: continue
            val phrase = entry.optString("phrase")
            val actionsJson = entry.optJSONArray("actions") ?: continue
            val actions = (0 until actionsJson.length()).map { actionsJson.optString(it) }
            val valid = propose(phrase, actions) ?: continue
            summaries.add("${valid.phrase}: ${valid.actions.joinToString(" → ")}")
        }
        summaries
    }

    companion object {
        val capabilities = setOf("volume_up", "volume_down", "app_update")
        private val lock = Any()
    }

    private fun read(): JSONArray = try {
        JSONArray(readValue())
    } catch (_: Exception) { JSONArray() }
}
