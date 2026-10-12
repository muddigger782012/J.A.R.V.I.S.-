package com.assistant.core.services

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Bounded local outcome journal. Never supplies AI context or command matches. */
class IntelligenceResultHistory internal constructor(
    private val read: () -> String?, private val write: (String?) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis
) {
    constructor(context: Context) : this(
        { context.applicationContext.getSharedPreferences("jarvis_intelligence_results", Context.MODE_PRIVATE).getString("entries", null) },
        { value ->
            val editor = context.applicationContext.getSharedPreferences("jarvis_intelligence_results", Context.MODE_PRIVATE).edit()
            if (value == null) editor.remove("entries") else editor.putString("entries", value)
            editor.commit()
        }
    )
    enum class Outcome(val label: String) {
        USER_CONFIRMED("User confirmed success"), DEVICE_VERIFIED("Device change verified"),
        USER_FAILED("User reported failure"), NOT_VERIFIED("Device change not verified"),
        UNCLEAR("Outcome unclear; confirmation still pending")
    }
    data class Entry(val time: Long, val phrase: String, val action: String, val outcome: Outcome, val detail: String)

    fun snapshot(): List<Entry> = synchronized(lock) {
        runCatching {
            val rows = JSONArray(read() ?: "[]")
            (0 until rows.length()).mapNotNull { index ->
                runCatching {
                    val row = rows.getJSONObject(index)
                    Entry(row.getLong("time"), row.getString("phrase"), row.getString("action"),
                        Outcome.valueOf(row.getString("outcome")), row.getString("detail")).takeIf(::valid)
                }.getOrNull()
            }.takeLast(LIMIT).asReversed()
        }.getOrDefault(emptyList())
    }

    fun record(phrase: String, action: String, outcome: Outcome, detail: String): Boolean = synchronized(lock) {
        val entry = Entry(now(), phrase, action, outcome, detail)
        if (!valid(entry)) return@synchronized false
        runCatching {
            val rows = JSONArray()
            (snapshot().asReversed() + entry).takeLast(LIMIT).forEach {
                rows.put(JSONObject().put("time", it.time).put("phrase", it.phrase).put("action", it.action)
                    .put("outcome", it.outcome.name).put("detail", it.detail))
            }
            write(rows.toString())
        }.getOrDefault(false)
    }

    fun clear(): Boolean = synchronized(lock) { runCatching { write(null) }.getOrDefault(false) }

    private fun valid(entry: Entry) = entry.time >= 0 && entry.phrase.isNotBlank() && entry.phrase.length <= 240 &&
        entry.action.isNotBlank() && entry.action.length <= 160 && entry.detail.isNotBlank() && entry.detail.length <= 320

    companion object {
        const val LIMIT = 100
        private val lock = Any()
    }
}
