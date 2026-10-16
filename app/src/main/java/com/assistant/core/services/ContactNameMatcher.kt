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

    enum class MatchStatus { FOUND, NOT_FOUND, AMBIGUOUS }
    data class PhoneMatch(val number: String?, val status: MatchStatus)

    fun matchPhone(name: String, contacts: List<Pair<String, String>>): PhoneMatch {
        val requested = normalize(name)
        if (requested.isBlank()) return PhoneMatch(null, MatchStatus.NOT_FOUND)
        val exact = contacts.filter { normalize(it.first) == requested }
        val matches = if (exact.isNotEmpty()) exact else contacts.filter {
            normalize(it.first).split(' ').contains(requested)
        }
        if (matches.isEmpty()) return PhoneMatch(null, MatchStatus.NOT_FOUND)
        val names = matches.map { normalize(it.first) }.distinct()
        val numbers = matches.map { it.second.filter(Char::isDigit) }
            .filter { it.isNotBlank() }.distinct()
        if (names.size != 1 || numbers.size != 1) return PhoneMatch(null, MatchStatus.AMBIGUOUS)
        val number = matches.firstOrNull { it.second.filter(Char::isDigit) == numbers.single() }?.second
        return PhoneMatch(number, if (number == null) MatchStatus.NOT_FOUND else MatchStatus.FOUND)
    }

    fun uniquePhoneFor(name: String, contacts: List<Pair<String, String>>): String? =
        matchPhone(name, contacts).number
}
