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
    /** Explicit levels take precedence over legacy relative-volume aliases. */
    fun parse(input: String): Int? {
        val cleaned = input.trim().trimEnd('.', '?', '!').trim().replace(Regex("\\s+"), " ")
        val prefix = "(?i)^(?:please )?(?:set|put|turn|raise|increase|lower|decrease|bring) (?:my |the )?(?:media )?volume "
        Regex(prefix + "(?:(?:to|at) )?(0|[1-9][0-9]?|100)\\s*(?:%|percent)$")
            .matchEntire(cleaned)?.let { return it.groupValues[1].toIntOrNull() }
        Regex(prefix + "(?:to|at) (?:the )?(?:max(?:imum)?|full)(?: level| volume)?$")
            .matchEntire(cleaned)?.let { return 100 }
        Regex(prefix + "(?:to|at) (?:the )?min(?:imum)?(?: level| volume)?$")
            .matchEntire(cleaned)?.let { return 0 }
        Regex("(?i)^(?:please )?(?:turn|raise|increase|bring) (?:my |the )?(?:media )?volume (?:all the way|all way) up$")
            .matchEntire(cleaned)?.let { return 100 }
        Regex("(?i)^(?:please )?(?:turn|lower|decrease|bring) (?:my |the )?(?:media )?volume (?:all the way|all way) down$")
            .matchEntire(cleaned)?.let { return 0 }
        return null
    }
    fun conflictsWithRelative(input: String, capability: String): Boolean =
        capability in setOf("volume_up", "volume_down") && parse(input) != null
    data class Result(val executed: Boolean, val learned: Boolean)
    fun execute(phrase: String, percent: Int, learningEnabled: Boolean, set: (Int) -> Boolean,
        record: (String, Map<String, String>) -> Boolean): Result {
        val executed = percent in 0..100 && runCatching { set(percent) }.getOrDefault(false)
        val learned = executed && learningEnabled && runCatching { record(phrase, mapOf("percent" to percent.toString())) }.getOrDefault(false)
        return Result(executed, learned)
    }
}
