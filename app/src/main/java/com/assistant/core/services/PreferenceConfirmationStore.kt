package com.assistant.core.services

import android.content.Context
import org.json.JSONObject

class PreferenceConfirmationStore internal constructor(
    private val read: () -> String?, private val write: (String?) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis
) {
    constructor(context: Context) : this(
        { context.applicationContext.getSharedPreferences("jarvis_preference_confirmation", Context.MODE_PRIVATE).getString("pending", null) },
        { value ->
            val editor = context.applicationContext.getSharedPreferences("jarvis_preference_confirmation", Context.MODE_PRIVATE).edit()
            if (value == null) editor.remove("pending") else editor.putString("pending", value)
            editor.commit()
        }
    )
    data class Pending(val slot: String, val action: LearnableDeviceAction?, val approved: Boolean = false)
    fun propose(slot: String, action: LearnableDeviceAction? = null): Boolean = synchronized(lock) {
        if (slot != "clear" && (slot !in AssistantMemory.slots || action == null || action.capabilityId != "open_app" || !action.isValid())) return@synchronized false
        write(JSONObject().put("slot", slot).put("created", now()).apply {
            action?.let { put("parameters", JSONObject(it.parameters)) }
        }.toString())
    }
    fun consume(text: String): Pending? = synchronized(lock) {
        val raw = read() ?: return@synchronized null
        if (!write(null)) return@synchronized null
        if (!ConfirmationWords.isConfirmation(text)) return@synchronized null
        runCatching {
            val json = JSONObject(raw)
            if (now() - json.getLong("created") !in 0..120_000) return@runCatching null
            val slot = json.getString("slot")
            val action = if (slot == "clear") null else {
                if (slot !in AssistantMemory.slots) return@runCatching null
                val params = json.getJSONObject("parameters")
                LearnableDeviceAction("open_app", params.keys().asSequence().associateWith { params.getString(it) }).takeIf { it.isValid() } ?: return@runCatching null
            }
            Pending(slot, action, ConfirmationWords.approves(text))
        }.getOrNull()
    }
    companion object { private val lock = Any() }
}
