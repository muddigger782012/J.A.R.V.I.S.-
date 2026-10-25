package com.assistant.core.services

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Calendar intent is independent of forecast availability and of the phone's timezone. */
object ForecastDates {
    private val weekdays = DayOfWeek.values().associateBy { it.name.lowercase(Locale.ROOT) }
    private val dayPattern = Regex("\\b(?:(next|this)\\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b")
    fun selector(text: String): String? {
        val clean = text.lowercase(Locale.ROOT)
        // A correction names its final requested date, not the superseded date earlier in the sentence.
        val matches = dayPattern.findAll(clean).toList() +
            Regex("\\b(day after tomorrow|tomorrow|today|tonight)\\b").findAll(clean).toList()
        return matches.maxByOrNull { it.range.first }?.value
    }
    fun target(text: String, today: LocalDate): LocalDate? = when (val day = selector(text)) {
        "today", "tonight" -> today
        "tomorrow" -> today.plusDays(1)
        "day after tomorrow" -> today.plusDays(2)
        null -> null
        else -> {
            val weekday = weekdays.getValue(day.substringAfterLast(' '))
            // 'Next' is strictly after today. Always show the calendar date to disambiguate it.
            today.with(if (day.startsWith("next ")) TemporalAdjusters.next(weekday) else TemporalAdjusters.nextOrSame(weekday))
        }
    }
    /** Reconstruct only bounded weather context; an intervening unrelated request ends that context. */
    fun contextual(input: String, history: List<String>): String? {
        var previous: String? = null
        for (turn in history.takeLast(8)) previous = one(turn, previous)
        return one(input, previous)
    }
    private fun one(input: String, previous: String?): String? {
        if (input.length > 1000) return null
        val clean = input.trim().lowercase(Locale.ROOT)
        if (Regex("\\b(remind|send|text|message|call|dial|timer|alarm|routine|teach|learn|remember|approve|confirm|plan|schedule|cancel|task)\\b").containsMatchIn(clean)) return null
        val weather = OnlineAnswers.isWeather(clean)
        val date = selector(clean)
        val correction = previous != null && date != null &&
            Regex("^(?:(?:no|sorry|actually|instead|i meant|i mean|what about|how about|and|for|on)\\b[ ,:]*)*(?:next |this )?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|today|tonight|tomorrow|day after tomorrow)\\b.*[?.!]*$").matches(clean)
        if (!weather && !correction) return null
        val inherited = if (date == null && previous != null &&
            Regex("\\b(it|that|then|too|there)\\b").containsMatchIn(clean)) selector(previous) else null
        return if (correction) "weather for $date" else input + (inherited?.let { " for $it" } ?: "")
    }
}
