package com.assistant.core.services

/** A bounded media-volume level. No stream IDs or arbitrary arguments are accepted from AI. */
object ExactVolumeLearning {
    const val CAPABILITY = "volume_level"
    val capabilities = setOf(CAPABILITY)
    fun fromParameters(parameters: Map<String, String>): Int? =
        if (parameters.keys == setOf("percent")) RoutineSteps.volumePercent("volume:${parameters["percent"]}") else null
    fun fromLesson(lesson: FallbackIntentLesson): Int? {
        if (!FallbackLessonProtocol.accepts(lesson) || lesson.confidence < FallbackIntentLesson.TEACH_THRESHOLD ||
            lesson.intent != "DEVICE_SETTING" || lesson.entities.keys != setOf("setting", "percent") ||
            lesson.entities["setting"] != "media_volume") return null
        return fromParameters(mapOf("percent" to lesson.entities.getValue("percent")))
    }
    fun parse(input: String): Int? = Regex(
        "(?i)^(?:please )?(?:set|put|turn) (?:my |the )?(?:media )?volume (?:to|at) (0|[1-9][0-9]?|100)\\s*(?:%|percent)[?.!]*$"
    ).matchEntire(input.trim().replace(Regex("\\s+"), " "))?.groupValues?.get(1)?.toIntOrNull()
    data class Result(val executed: Boolean, val learned: Boolean)
    fun execute(phrase: String, percent: Int, learningEnabled: Boolean, set: (Int) -> Boolean,
        record: (String, Map<String, String>) -> Boolean): Result {
        val executed = percent in 0..100 && runCatching { set(percent) }.getOrDefault(false)
        val learned = executed && learningEnabled && runCatching { record(phrase, mapOf("percent" to percent.toString())) }.getOrDefault(false)
        return Result(executed, learned)
    }
}
