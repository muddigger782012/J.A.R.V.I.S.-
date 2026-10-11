package com.assistant.core.services

/** AI supplies a slot and launcher name; only the host can bind an installed package. */
data class LearnablePreference(val slot: String, val action: LearnableDeviceAction) {
    fun isValid(): Boolean = slot in AssistantMemory.slots && action.capabilityId == "open_app" && action.isValid()
    fun parameters(): Map<String, String> = action.parameters + ("slot" to slot)

    companion object {
        const val CAPABILITY = "set_preference"
        val capabilities = setOf(CAPABILITY)
        fun fromParameters(parameters: Map<String, String>): LearnablePreference? {
            if (parameters.keys != setOf("slot", "package", "label")) return null
            return LearnablePreference(parameters.getValue("slot"), LearnableDeviceAction("open_app", parameters - "slot")).takeIf { it.isValid() }
        }
        fun fromLesson(lesson: FallbackIntentLesson, resolveApp: (String) -> Pair<String, String>?): LearnablePreference? {
            if (!lesson.isValid() || lesson.confidence < FallbackIntentLesson.TEACH_THRESHOLD ||
                lesson.intent != "SET_APP_PREFERENCE" || lesson.entities.keys != setOf("slot", "app_name")) return null
            val slot = lesson.entities.getValue("slot")
            if (slot !in AssistantMemory.slots) return null
            val name = lesson.entities.getValue("app_name").trim().takeIf { it.isNotBlank() && it.length <= 100 } ?: return null
            val target = resolveApp(name) ?: return null
            return LearnablePreference(slot, LearnableDeviceAction("open_app", mapOf("package" to target.first, "label" to target.second))).takeIf { it.isValid() }
        }
    }
}

/** A preference is verified by durable write and readback; it never opens the app. */
object PreferenceLearning {
    data class Outcome(val saved: Boolean, val learned: Boolean)
    fun commit(
        pending: PreferenceConfirmationStore.Pending,
        learningEnabled: Boolean,
        installed: (LearnableDeviceAction) -> Boolean,
        save: (String, LearnableDeviceAction) -> Boolean,
        read: (String) -> LearnableDeviceAction?,
        record: (String, LearnablePreference) -> Boolean
    ): Outcome {
        val action = pending.action ?: return Outcome(false, false)
        val preference = LearnablePreference(pending.slot, action)
        if (!pending.approved || !preference.isValid()) return Outcome(false, false)
        val saved = runCatching { installed(action) && save(pending.slot, action) && read(pending.slot) == action }.getOrDefault(false)
        if (!saved) return Outcome(false, false)
        val phrase = pending.learningPhrase
        val learned = learningEnabled && phrase != null && PreferenceConfirmationStore.validLearningPhrase(phrase) &&
            runCatching { record(phrase, preference) }.getOrDefault(false)
        return Outcome(true, learned)
    }
}
