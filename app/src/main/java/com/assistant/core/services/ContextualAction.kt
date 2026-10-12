package com.assistant.core.services

import org.json.JSONArray
import org.json.JSONObject

/** A learned reference resolves against current memory, never a frozen AI-supplied target. */
data class ContextualAction(val capabilityId: String, val parameters: Map<String, String>) {
    fun isValid(): Boolean = when (capabilityId) {
        "open_preferred" -> parameters.keys == setOf("slot") && parameters["slot"] in AssistantMemory.slots
        "repeat_action" -> parameters.keys == setOf("kind") && parameters["kind"] in setOf("timer", "open_app", "most_recent")
        else -> false
    }

    fun resolve(memory: AssistantMemory): LearnableDeviceAction? = if (!isValid()) null else when (capabilityId) {
        "open_preferred" -> memory.preferred(parameters.getValue("slot"))
        else -> if (parameters["kind"] == "most_recent") memory.mostRecentConfirmed()
            else memory.lastConfirmed(parameters.getValue("kind"))
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
            memory.mostRecentConfirmed()?.let { recent.put("most_recent", JSONObject()
                .put("kind", it.capabilityId).put("description", it.description())) }
            return JSONObject().put("preferred_apps", preferred).put("confirmed_actions", recent)
                .put("capabilities", JSONArray(listOf("DEVICE_SETTING: media_volume direction up/down", "SET_TIMER: seconds 1..86400",
                    "OPEN_APP: app_name", "OPEN_PREFERRED_APP: slot browser/maps/music", "REPEAT_ACTION: kind timer/open_app/most_recent", "SET_APP_PREFERENCE: slot browser/maps/music and app_name", "CREATE_ROUTINE: phrase and steps (comma-separated volume_up/volume_down/volume:percent/app_update/timer:seconds/preferred:slot/open:exact app name; 1..3 distinct steps; one timer or app target at most, last, no updater with either; volume percent 0..100 explicit integer, one percentage step and no up/down with it; timer 1..86400 seconds; preferred slot browser/maps/music must be saved; named apps resolved locally, no packages)"))).toString()
        }
    }
}
