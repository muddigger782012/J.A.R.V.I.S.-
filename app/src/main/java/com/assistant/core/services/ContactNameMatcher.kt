package com.assistant.core.services

import java.text.Normalizer
import java.util.Locale

/** Conservative speech-to-text name normalization; never fuzzy-match call recipients. */
object ContactNameMatcher {
    fun normalize(raw: String): String =
        Normalizer.normalize(raw.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\bkey dog\\b"), "keydawg")
            .replace(Regex("\\bkey dawg\\b"), "keydawg")
            .replace(Regex("\\bfreddy\\b"), "freddie")

    enum class MatchStatus { FOUND, NOT_FOUND, AMBIGUOUS }
    data class MatchResult(val status: MatchStatus, val number: String? = null)

    fun resolve(name: String, contacts: List<Pair<String, String>>): MatchResult {
        val requested = normalize(name)
        if (requested.isBlank()) return MatchResult(MatchStatus.NOT_FOUND)
        val exact = contacts.filter { normalize(it.first) == requested }
        val matches = if (exact.isNotEmpty()) exact else contacts.filter {
            normalize(it.first).split(' ').contains(requested)
        }
        if (matches.isEmpty()) return MatchResult(MatchStatus.NOT_FOUND)
        if (matches.map { normalize(it.first) }.distinct().size != 1) return MatchResult(MatchStatus.AMBIGUOUS)
        val numbers = matches.map { it.second.filter(Char::isDigit) }.filter { it.isNotBlank() }.distinct()
        if (numbers.size != 1) return MatchResult(MatchStatus.AMBIGUOUS)
        return MatchResult(MatchStatus.FOUND, matches.first { it.second.filter(Char::isDigit) == numbers.single() }.second)
    }

    /** Return a number only when all matching rows resolve to one phone number. */
    fun uniquePhoneFor(name: String, contacts: List<Pair<String, String>>): String? =
        resolve(name, contacts).number
}
