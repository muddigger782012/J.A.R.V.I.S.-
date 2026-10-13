package com.assistant.core.services

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Private bounded feature-request backlog. Never used as AI context or executable learning. */
class CapabilityRequestStore internal constructor(
    private val read: () -> String?, private val write: (String?) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis
) {
    constructor(context: Context) : this(
        { context.applicationContext.getSharedPreferences("jarvis_capability_requests", Context.MODE_PRIVATE).getString("entries", null) },
        { value ->
            val editor = context.applicationContext.getSharedPreferences("jarvis_capability_requests", Context.MODE_PRIVATE).edit()
            if (value == null) editor.remove("entries") else editor.putString("entries", value)
            editor.commit()
        }
    )
    data class Entry(val text: String, val created: Long)
    enum class Result { ADDED, DUPLICATE, FULL, INVALID, FAILED }
    private fun clean(text: String) = text.trim().trimEnd('.', '?', '!').trim().replace(Regex("\\s+"), " ")
    private fun key(text: String) = clean(text).lowercase(Locale.ROOT)
    private fun valid(entry: Entry) = entry.created >= 0 && entry.text.isNotBlank() && entry.text.length <= MAX_TEXT

    fun snapshot(): List<Entry> = synchronized(lock) {
        runCatching {
            val rows = JSONArray(read() ?: "[]")
            (0 until rows.length()).mapNotNull { index -> runCatching {
                val row = rows.getJSONObject(index)
                Entry(row.getString("text"), row.getLong("created")).takeIf(::valid)
            }.getOrNull() }.distinctBy { key(it.text) }.takeLast(LIMIT).asReversed()
        }.getOrDefault(emptyList())
    }
    fun find(text: String): Entry? = synchronized(lock) {
        val wanted = key(text)
        if (wanted.isBlank() || wanted.length > MAX_TEXT) null else snapshot().singleOrNull { key(it.text) == wanted }
    }
    /** Numbers refer to the current list. Callers bind its action before asking approval. */
    fun findReference(text: String): Entry? = synchronized(lock) {
        val reference = clean(text)
        if (!Regex("(?i)^number(?: |$)").containsMatchIn(reference)) return@synchronized find(reference)
        val number = Regex("(?i)^number ([1-9][0-9]*)$").matchEntire(reference)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return@synchronized null
        if (number !in 1..LIMIT) null else snapshot().getOrNull(number - 1)
    }
    fun add(text: String): Result = synchronized(lock) {
        val entry = Entry(clean(text), now())
        if (!valid(entry)) return@synchronized Result.INVALID
        val current = snapshot()
        if (current.any { key(it.text) == key(entry.text) }) return@synchronized Result.DUPLICATE
        if (current.size >= LIMIT) return@synchronized Result.FULL
        if (persist(current.asReversed() + entry)) Result.ADDED else Result.FAILED
    }
    fun forget(text: String): Boolean = synchronized(lock) {
        if (key(text).isBlank()) return@synchronized false
        val current = snapshot().asReversed()
        val remaining = current.filterNot { key(it.text) == key(text) }
        remaining.size < current.size && persist(remaining)
    }
    private fun persist(entries: List<Entry>): Boolean = runCatching {
        val rows = JSONArray()
        entries.forEach { rows.put(JSONObject().put("text", it.text).put("created", it.created)) }
        write(rows.toString())
    }.getOrDefault(false)
    companion object { const val LIMIT = 20; const val MAX_TEXT = 240; private val lock = Any() }
}
