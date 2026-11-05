package com.assistant.core.services

import java.util.Locale

/** An explicit US city must never silently use the phone's saved weather coordinates. */
internal object WeatherPlaceQuery {
    data class Place(val city: String, val state: String?)
    data class Candidate(val city: String, val state: String, val country: String, val latitude: Double, val longitude: Double)
    private val states = mapOf(
        "AL" to "Alabama", "AK" to "Alaska", "AZ" to "Arizona", "AR" to "Arkansas",
        "CA" to "California", "CO" to "Colorado", "CT" to "Connecticut", "DE" to "Delaware",
        "FL" to "Florida", "GA" to "Georgia", "HI" to "Hawaii", "ID" to "Idaho",
        "IL" to "Illinois", "IN" to "Indiana", "IA" to "Iowa", "KS" to "Kansas",
        "KY" to "Kentucky", "LA" to "Louisiana", "ME" to "Maine", "MD" to "Maryland",
        "MA" to "Massachusetts", "MI" to "Michigan", "MN" to "Minnesota", "MS" to "Mississippi",
        "MO" to "Missouri", "MT" to "Montana", "NE" to "Nebraska", "NV" to "Nevada",
        "NH" to "New Hampshire", "NJ" to "New Jersey", "NM" to "New Mexico",
        "NY" to "New York", "NC" to "North Carolina", "ND" to "North Dakota",
        "OH" to "Ohio", "OK" to "Oklahoma", "OR" to "Oregon", "PA" to "Pennsylvania",
        "RI" to "Rhode Island", "SC" to "South Carolina", "SD" to "South Dakota",
        "TN" to "Tennessee", "TX" to "Texas", "UT" to "Utah", "VT" to "Vermont",
        "VA" to "Virginia", "WA" to "Washington", "WV" to "West Virginia",
        "WI" to "Wisconsin", "WY" to "Wyoming", "DC" to "District of Columbia"
    )
    private val excluded = setOf("today", "tonight", "tomorrow", "this afternoon", "this evening", "next week", "this week")
    fun parse(question: String): Place? {
        if (!OnlineAnswers.isWeather(question)) return null
        val tail = Regex("""\b(?:in|for|at|near)\s+([A-Za-z][A-Za-z,.' -]{1,85})""", RegexOption.IGNORE_CASE)
            .findAll(question).lastOrNull()?.groupValues?.get(1)?.trim() ?: return null
        val candidate = tail.replace(
            Regex("""\s+\b(?:today|tonight|tomorrow|right now|this afternoon|this evening|this week|next week)\b.*$""",
                RegexOption.IGNORE_CASE), ""
        ).trim().trimEnd('.', ',', '!', '?')
        if (candidate.isBlank() || candidate.lowercase(Locale.ROOT) in excluded) return null
        val state = states.values.sortedByDescending(String::length).firstOrNull {
            candidate.endsWith(" $it", ignoreCase = true) || candidate.endsWith(", $it", ignoreCase = true)
        } ?: states.entries.firstOrNull {
            candidate.endsWith(" ${it.key}", ignoreCase = true) || candidate.endsWith(", ${it.key}", ignoreCase = true)
        }?.value
        val suffix = if (state == null) "" else {
            val code = states.entries.first { it.value == state }.key
            if (candidate.endsWith(" $state", true) || candidate.endsWith(", $state", true)) state else code
        }
        val city = if (suffix.isEmpty()) candidate else candidate.dropLast(suffix.length).trimEnd(' ', ',')
        if (!Regex("""[a-zA-Z][a-zA-Z .'-]{1,55}""").matches(city)) return null
        return Place(city.trim(), state)
    }

    fun correctedLocation(input: String): Place? {
        if (!Regex("""^(?:no[,.]?|sorry[,.]?|actually[,.]?|i (?:said|meant|mean|wanted)\b)""",
                RegexOption.IGNORE_CASE).containsMatchIn(input.trim())) return null
        val named = Regex("""\b(?:about|in|for|meant)\s+([A-Za-z][A-Za-z,.' -]{1,75})\s*[.!?]*$""",
            RegexOption.IGNORE_CASE).findAll(input).lastOrNull()?.groupValues?.get(1)?.trim() ?: return null
        val place = parse("weather in $named") ?: return null
        // Without a state this could be "I meant next Sunday", a calendar
        // correction rather than a named city. Keep those in ForecastDates.
        return place.takeIf { it.state != null }
    }

    fun choose(place: Place, candidates: List<Candidate>): Candidate? {
        val eligible = candidates.filter {
            it.country.equals("US", ignoreCase = true) &&
            it.city.equals(place.city, ignoreCase = true) &&
            (place.state == null || it.state.equals(place.state, ignoreCase = true)) &&
            it.latitude.isFinite() && it.longitude.isFinite() &&
            it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
        }
        if (place.state == null && eligible.map { it.state }.distinct().size > 1) return null
        return eligible.firstOrNull()
    }
}
