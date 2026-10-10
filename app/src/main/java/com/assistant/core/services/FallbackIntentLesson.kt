package com.assistant.core.services

import org.json.JSONObject

/**
 * Machine-readable lesson returned by a fallback intelligence provider.
 * This deliberately contains no executable code. Learned interpretations must
 * still be routed through JARVIS' existing capability and policy layers.
 */
data class FallbackIntentLesson(
    val intent: String,
    val confidence: Double,
    val entities: Map<String, String> = emptyMap(),
    val answer: String? = null,
    val learnable: Boolean = false
) {
    fun isValid(): Boolean =
        INTENT_PATTERN.matches(intent) &&
            confidence in 0.0..1.0 &&
            entities.size <= MAX_ENTITIES &&
            entities.all { (key, value) ->
                ENTITY_KEY_PATTERN.matches(key) && value.length <= MAX_ENTITY_LENGTH
            }

    fun canTeachLocally(): Boolean = isValid() && learnable && confidence >= TEACH_THRESHOLD

    companion object {
        const val TEACH_THRESHOLD = 0.90
        private const val MAX_ENTITIES = 12
        private const val MAX_ENTITY_LENGTH = 256
        private val INTENT_PATTERN = Regex("^[A-Z][A-Z0-9_]{1,63}$")
        private val ENTITY_KEY_PATTERN = Regex("^[a-z][a-z0-9_]{0,63}$")

        fun fromJson(raw: String): FallbackIntentLesson? = runCatching {
            val json = JSONObject(raw)
            val entitiesJson = json.optJSONObject("entities") ?: JSONObject()
            val entities = buildMap {
                entitiesJson.keys().forEach { key -> put(key, entitiesJson.optString(key)) }
            }
            FallbackIntentLesson(
                intent = json.getString("intent").trim(),
                confidence = json.optDouble("confidence", 0.0),
                entities = entities,
                answer = json.optString("answer").takeIf { it.isNotBlank() },
                learnable = json.optBoolean("learnable", false)
            ).takeIf { it.isValid() }
        }.getOrNull()
    }
}

/**
 * The strict response contract the cloud fallback should be instructed to use.
 */
object FallbackLessonProtocol {
    const val VERSION = 1
    val supportedIntents = setOf(
        "GENERAL_QUESTION",
        "WEATHER_CURRENT",
        "WEATHER_FORECAST",
        "CALL_CONTACT",
        "SEND_MESSAGE",
        "OPEN_APP",
        "NAVIGATE",
        "SET_ALARM",
        "SET_TIMER",
        "CREATE_REMINDER",
        "MEDIA_CONTROL",
        "DEVICE_SETTING"
    )

    fun accepts(lesson: FallbackIntentLesson): Boolean =
        lesson.isValid() && lesson.intent in supportedIntents
}
