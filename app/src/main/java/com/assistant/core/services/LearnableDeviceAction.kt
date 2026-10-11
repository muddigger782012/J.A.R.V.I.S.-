package com.assistant.core.services

/** Bound parameters for existing timer/app-launch handlers, never executable code. */
data class LearnableDeviceAction(val capabilityId: String, val parameters: Map<String, String>) {
    fun isValid(): Boolean = when (capabilityId) {
        "timer" -> parameters.keys == setOf("seconds") && parameters["seconds"]?.toIntOrNull()?.let { it in 1..86400 } == true
        "open_app" -> parameters.keys == setOf("package", "label") &&
            parameters["package"]?.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+")) == true &&
            parameters["label"]?.let { it.isNotBlank() && it.length <= 100 } == true
        else -> false
    }

    fun description(): String = when (capabilityId) {
        "timer" -> "start a ${parameters["seconds"]}-second timer"
        "open_app" -> "open ${parameters["label"]}"
        else -> "perform an unsupported action"
    }

    companion object {
        val capabilities = setOf("timer", "open_app")
        fun fromLesson(lesson: FallbackIntentLesson, resolveApp: (String) -> Pair<String, String>?): LearnableDeviceAction? {
            if (!FallbackLessonProtocol.accepts(lesson) || lesson.confidence < FallbackIntentLesson.TEACH_THRESHOLD) return null
            return when (lesson.intent) {
                "SET_TIMER" -> if (lesson.entities.keys == setOf("seconds")) LearnableDeviceAction("timer", lesson.entities) else null
                "OPEN_APP" -> {
                    if (lesson.entities.keys != setOf("app_name")) return null
                    val name = lesson.entities["app_name"]?.trim()?.takeIf { it.isNotBlank() && it.length <= 100 } ?: return null
                    val app = resolveApp(name) ?: return null
                    LearnableDeviceAction("open_app", mapOf("package" to app.first, "label" to app.second))
                }
                else -> null
            }?.takeIf { it.isValid() }
        }
    }
}
