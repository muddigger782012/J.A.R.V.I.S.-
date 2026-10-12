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

    /** Ordering is recorded only by a new verified outcome, never guessed from legacy slots. */
    fun mostRecentConfirmed(): LearnableDeviceAction? = synchronized(lock) {
        val json = load()
        val kind = json.optString("last_action_kind")
        if (kind !in LearnableDeviceAction.capabilities) return@synchronized null
        decode(json.optJSONObject("last_$kind"))?.takeIf { it.capabilityId == kind }
    }

    fun savePreference(slot: String, action: LearnableDeviceAction): Boolean = synchronized(lock) {
        if (slot !in slots || action.capabilityId != "open_app" || !action.isValid()) return@synchronized false
        save(load().put("preferred_$slot", encode(action)))
    }

    fun recordConfirmed(action: LearnableDeviceAction): Boolean = synchronized(lock) {
        if (!action.isValid()) return@synchronized false
        save(load().put("last_${action.capabilityId}", encode(action)).put("last_action_kind", action.capabilityId))
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
        if (key !in slots.map { "preferred_$it" } + listOf("last_timer", "last_open_app", "last_action_kind")) return@synchronized false
        val json = load()
        if (!json.has(key)) return@synchronized false
        json.remove(key)
        if (key == "last_${json.optString("last_action_kind")}") json.remove("last_action_kind")
        save(json)
    }

    fun clear(): Boolean = synchronized(lock) { save(JSONObject().put("seeded", true)) }

    fun summary(): String = synchronized(lock) {
        val lines = slots.mapNotNull { slot -> preferred(slot)?.let { "Preferred $slot: ${it.parameters["label"]}" } } +
            listOf("timer", "open_app").mapNotNull { kind -> lastConfirmed(kind)?.let { "Last confirmed $kind: ${it.description()}" } }
        val ordered = mostRecentConfirmed()?.let { "Most recent confirmed action: ${it.description()}" }
        if (ordered != null) return@synchronized (lines + ordered).joinToString("\n")
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
    private const val SLOT_WORDS = "browser|maps(?: app)?|navigation(?: app)?|music(?: app)?"
    private fun slotName(value: String) = value.lowercase(java.util.Locale.ROOT).removeSuffix(" app").let { if (it == "navigation") "maps" else it }
    private fun normalized(text: String) = text.lowercase(java.util.Locale.ROOT).trim().trimEnd('.', '?', '!').trim().replace(Regex("\\s+"), " ")
    fun preference(text: String): Preference? {
        val cleaned = text.trim().trimEnd('.', '?', '!').trim().replace(Regex("\\s+"), " ")
        val forward = Regex("(?i)^(?:please )?(?:(?:remember|save)(?: that)? |(?:set|change|update) )?(?:(?:my|the) )?preferred ($SLOT_WORDS) (?:is|as|to) (.{1,100})$")
            .matchEntire(cleaned)
        if (forward != null) return Preference(slotName(forward.groupValues[1]), forward.groupValues[2].trim())
        val reverse = Regex("(?i)^(?:please )?(?:set|make) (.{1,100}?) (?:(?:as|to be) (?:(?:my|the) )?|(?:my|the) )preferred ($SLOT_WORDS)$")
            .matchEntire(cleaned) ?: return null
        return Preference(slotName(reverse.groupValues[2]), reverse.groupValues[1].trim())
    }
    fun preferredSlot(text: String): String? {
        val cleaned = normalized(text)
        Regex("^(?:please )?(?:open|use|launch|start) (?:(?:my|the) )?preferred ($SLOT_WORDS)(?: please)?$").matchEntire(cleaned)?.let { return slotName(it.groupValues[1]) }
        return Regex("^(?:please )?(?:open|use|reopen|launch) (?:(?:the|that) )?same ($SLOT_WORDS)(?: again)?(?: please)?$")
            .matchEntire(cleaned)?.groupValues?.get(1)?.let(::slotName)
    }
    fun repeatCapability(text: String): String? {
        val cleaned = normalized(text)
        if (cleaned in setOf("do that again", "repeat that", "repeat that again", "do the same thing again",
                "repeat the last confirmed action", "repeat my last confirmed action", "do that again please", "please do that again")) return "most_recent"
        if (Regex("^(?:(?:use|start|repeat) )?(?:(?:the|that) )?same timer(?: again)?$").matches(cleaned) ||
            Regex("^(?:use|start|repeat) (?:the )?last (?:confirmed )?timer(?: again)?$").matches(cleaned)) return "timer"
        if (Regex("^(?:open|reopen) (?:(?:the|that) )?same app(?: again)?$").matches(cleaned) ||
            Regex("^(?:open|reopen) (?:the )?last (?:confirmed )?app(?: again)?$").matches(cleaned)) return "open_app"
        return null
    }
    fun forgetKey(text: String): String? {
        val cleaned = normalized(text)
        Regex("^(?:forget|remove) (?:(?:my|the) )?preferred ($SLOT_WORDS)$").matchEntire(cleaned)?.let { return "preferred_${slotName(it.groupValues[1])}" }
        return when (cleaned) { "forget the last timer" -> "last_timer"; "forget the last app" -> "last_open_app"; "forget the last action" -> "last_action_kind"; else -> null }
    }
}
