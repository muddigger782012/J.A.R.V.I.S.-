package com.assistant.core.services

import android.content.Context
import org.json.JSONObject

/** Bounded device-only preferences and confirmed action context, not chat transcripts. */
class AssistantMemory internal constructor(private val read: () -> String, private val write: (String) -> Boolean) {
    constructor(context: Context) : this(
        { context.applicationContext.getSharedPreferences("jarvis_assistant_memory", Context.MODE_PRIVATE).getString("memory", "{}") ?: "{}" },
        { value -> context.applicationContext.getSharedPreferences("jarvis_assistant_memory", Context.MODE_PRIVATE).edit().putString("memory", value).commit() }
    )

    fun preferred(slot: String): LearnableDeviceAction? = synchronized(lock) { decode(load().optJSONObject("preferred_$slot")) }
    fun lastConfirmed(capability: String): LearnableDeviceAction? = synchronized(lock) { decode(load().optJSONObject("last_$capability")) }

    fun savePreference(slot: String, action: LearnableDeviceAction): Boolean = synchronized(lock) {
        if (slot !in slots || action.capabilityId != "open_app" || !action.isValid()) return@synchronized false
        save(load().put("preferred_$slot", encode(action)))
    }

    fun recordConfirmed(action: LearnableDeviceAction): Boolean = synchronized(lock) {
        if (!action.isValid()) return@synchronized false
        save(load().put("last_${action.capabilityId}", encode(action)))
    }

    /** Import earlier user-verified lessons once; clearing memory must not resurrect them. */
    fun seedRecentOnce(timer: LearnableDeviceAction?, app: LearnableDeviceAction?): Boolean = synchronized(lock) {
        val json = load()
        if (json.optBoolean("seeded")) return@synchronized true
        listOfNotNull(timer, app).filter { it.isValid() }.forEach {
            if (!json.has("last_${it.capabilityId}")) json.put("last_${it.capabilityId}", encode(it))
        }
        save(json.put("seeded", true))
    }

    fun forget(key: String): Boolean = synchronized(lock) {
        if (key !in slots.map { "preferred_$it" } + listOf("last_timer", "last_open_app")) return@synchronized false
        val json = load()
        if (!json.has(key)) return@synchronized false
        json.remove(key)
        save(json)
    }

    fun clear(): Boolean = synchronized(lock) { save(JSONObject().put("seeded", true)) }

    fun summary(): String = synchronized(lock) {
        val lines = slots.mapNotNull { slot -> preferred(slot)?.let { "Preferred $slot: ${it.parameters["label"]}" } } +
            listOf("timer", "open_app").mapNotNull { kind -> lastConfirmed(kind)?.let { "Last confirmed $kind: ${it.description()}" } }
        if (lines.isEmpty()) "No assistant preferences or confirmed action context are saved on this device." else lines.joinToString("\n")
    }

    private fun load() = runCatching { JSONObject(read()) }.getOrDefault(JSONObject())
    private fun save(json: JSONObject) = runCatching { write(json.toString()) }.getOrDefault(false)
    private fun encode(action: LearnableDeviceAction) = JSONObject().put("capability", action.capabilityId).put("parameters", JSONObject(action.parameters))
    private fun decode(json: JSONObject?): LearnableDeviceAction? = runCatching {
        if (json == null) return null
        val params = json.getJSONObject("parameters")
        LearnableDeviceAction(json.getString("capability"), params.keys().asSequence().associateWith { params.getString(it) }).takeIf { it.isValid() }
    }.getOrNull()

    companion object {
        val slots = setOf("browser", "maps", "music")
        private val lock = Any()
    }
}

object AssistantMemoryCommands {
    data class Preference(val slot: String, val appName: String)
    private fun normalized(text: String) = text.lowercase(java.util.Locale.ROOT).trim().trimEnd('.', '?', '!').trim().replace(Regex("\\s+"), " ")
    fun preference(text: String): Preference? {
        val match = Regex("(?i)^(?:remember (?:that )?)?my preferred (browser|maps(?: app)?|music(?: app)?) is (.{1,100})[.!]?$")
            .matchEntire(text.trim()) ?: return null
        return Preference(match.groupValues[1].lowercase(java.util.Locale.ROOT).removeSuffix(" app"), match.groupValues[2].trim().trimEnd('.', '!'))
    }
    fun preferredSlot(text: String): String? {
        val cleaned = normalized(text)
        Regex("^(?:open|use) my preferred (browser|maps|music)(?: app)?$").matchEntire(cleaned)?.let { return it.groupValues[1] }
        return Regex("^(?:open|use|reopen) (?:(?:the|that) )?same (browser|maps|music)(?: app)?(?: again)?$")
            .matchEntire(cleaned)?.groupValues?.get(1)
    }
    fun repeatCapability(text: String): String? {
        val cleaned = normalized(text)
        if (Regex("^(?:(?:use|start|repeat) )?(?:(?:the|that) )?same timer(?: again)?$").matches(cleaned) ||
            Regex("^(?:use|start|repeat) (?:the )?last (?:confirmed )?timer(?: again)?$").matches(cleaned)) return "timer"
        if (Regex("^(?:open|reopen) (?:(?:the|that) )?same app(?: again)?$").matches(cleaned) ||
            Regex("^(?:open|reopen) (?:the )?last (?:confirmed )?app(?: again)?$").matches(cleaned)) return "open_app"
        return null
    }
    fun forgetKey(text: String): String? {
        val cleaned = normalized(text)
        Regex("^forget my preferred (browser|maps|music)(?: app)?$").matchEntire(cleaned)?.let { return "preferred_${it.groupValues[1]}" }
        return when (cleaned) { "forget the last timer" -> "last_timer"; "forget the last app" -> "last_open_app"; else -> null }
    }
}
