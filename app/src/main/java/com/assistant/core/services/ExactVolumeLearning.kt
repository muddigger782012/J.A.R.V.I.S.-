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
        val cleaned = LocalDeviceCommand.volumeRequest(input)
        if (cleaned.length > 240) return null
        val subject = "(?:my |the )?(?:(?:(?:media|music) )?(?:volume|sound)|music)"
        val prefix = "^(?:set|put|turn|raise|increase|lower|decrease|bring|boost) $subject "
        Regex(prefix + "(?:(?:to|at) )?(0|[1-9][0-9]?|100|[a-z]+(?:[ -][a-z]+)*)\\s*(?:%|percent|per cent)$")
            .matchEntire(cleaned)?.let { return percent(it.groupValues[1]) }
        Regex(prefix + "(?:to|at) (?:the )?(?:max(?:imum)?|full)(?: level| volume)?$")
            .matchEntire(cleaned)?.let { return 100 }
        Regex(prefix + "(?:to|at) (?:the )?min(?:imum)?(?: level| volume)?$")
            .matchEntire(cleaned)?.let { return 0 }
        Regex(prefix + "(?:to|at) (half|a half|one half|halfway|half way|quarter|a quarter|one quarter|three[ -]quarters?)(?: volume)?$")
            .matchEntire(cleaned)?.let {
                return when (it.groupValues[1]) { "half", "a half", "one half", "halfway", "half way" -> 50
                    "quarter", "a quarter", "one quarter" -> 25
                    else -> 75 }
            }
        Regex("^(?:turn|raise|increase|bring|boost) $subject (?:all (?:the )?way up|up all (?:the )?way)$")
            .matchEntire(cleaned)?.let { return 100 }
        Regex("^(?:turn|lower|decrease|bring) $subject (?:all (?:the )?way down|down all (?:the )?way)$")
            .matchEntire(cleaned)?.let { return 0 }
        return null
    }
    private fun percent(value: String): Int? {
        value.toIntOrNull()?.let { return it.takeIf { n -> n in 0..100 } }
        val words = value.replace('-', ' ').trim().split(' ')
        val small = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
        if (words.size == 1) small.indexOf(words.single()).takeIf { it >= 0 }?.let { return it }
        if (words == listOf("one", "hundred") || words == listOf("a", "hundred") || words == listOf("hundred")) return 100
        val tens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
            "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
        val base = tens[words.firstOrNull()] ?: return null
        if (words.size == 1) return base
        if (words.size != 2) return null
        return small.indexOf(words[1]).takeIf { it in 1..9 }?.let { base + it }
    }
    fun conflictsWithRelative(input: String, capability: String): Boolean =
        capability in setOf("volume_up", "volume_down") && parse(input) != null
    fun relativeConflictGuidance(input: String, capability: String): String? =
        if (conflictsWithRelative(input, capability)) {
            val percent = parse(input)!!
            "That phrase already requests media volume at $percent%. The one-step interpretation was cancelled. " +
                "Say 'set media volume to $percent percent' to request the exact level, or choose another phrase for a one-step shortcut. Nothing was changed or learned."
        } else null
    data class Result(val executed: Boolean, val learned: Boolean)
    fun execute(phrase: String, percent: Int, learningEnabled: Boolean, set: (Int) -> Boolean,
        record: (String, Map<String, String>) -> Boolean): Result {
        val executed = percent in 0..100 && runCatching { set(percent) }.getOrDefault(false)
        val learned = executed && learningEnabled && runCatching { record(phrase, mapOf("percent" to percent.toString())) }.getOrDefault(false)
        return Result(executed, learned)
    }
}
