package com.assistant.core.services

import org.json.JSONArray
import org.json.JSONObject

/** A learned reference resolves against current memory, never a frozen AI-supplied target. */
data class ContextualAction(val capabilityId: String, val parameters: Map<String, String>) {
    fun isValid(): Boolean = when (capabilityId) {
        "open_preferred" -> parameters.keys == setOf("slot") && parameters["slot"] in AssistantMemory.slots
        "repeat_action" -> parameters.keys == setOf("kind") && parameters["kind"] in setOf("timer", "open_app")
        else -> false
    }

    fun resolve(memory: AssistantMemory): LearnableDeviceAction? = if (!isValid()) null else when (capabilityId) {
        "open_preferred" -> memory.preferred(parameters.getValue("slot"))
        else -> memory.lastConfirmed(parameters.getValue("kind"))
    }

    companion object {
        val capabilities = setOf("open_preferred", "repeat_action")
        fun fromLesson(lesson: FallbackIntentLesson): ContextualAction? {
            if (!lesson.canTeachLocally()) return null
            val capability = when (lesson.intent) {
                "OPEN_PREFERRED_APP" -> "open_preferred"
                "REPEAT_ACTION" -> "repeat_action"
                else -> return null
            }
            return ContextualAction(capability, lesson.entities).takeIf { it.isValid() }
        }

        /** Only approved app labels and verified recent actions; no packages or transcript. */
        fun aiContext(memory: AssistantMemory): String {
            val preferred = JSONObject()
            AssistantMemory.slots.forEach { slot -> memory.preferred(slot)?.let { preferred.put(slot, it.parameters.getValue("label")) } }
            val recent = JSONObject()
            memory.lastConfirmed("timer")?.let { recent.put("timer", JSONObject().put("seconds", it.parameters.getValue("seconds"))) }
            memory.lastConfirmed("open_app")?.let { recent.put("open_app", JSONObject().put("label", it.parameters.getValue("label"))) }
            return JSONObject().put("preferred_apps", preferred).put("confirmed_actions", recent)
                .put("capabilities", JSONArray(listOf("DEVICE_SETTING: media_volume direction up/down", "SET_TIMER: seconds 1..86400",
                    "OPEN_APP: app_name", "OPEN_PREFERRED_APP: slot browser/maps/music", "REPEAT_ACTION: kind timer/open_app"))).toString()
        }
    }
}
