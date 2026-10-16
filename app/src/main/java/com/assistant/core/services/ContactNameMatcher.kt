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

    /** Return a number only when all matching rows resolve to one phone number. */
    fun uniquePhoneFor(name: String, contacts: List<Pair<String, String>>): String? {
        val requested = normalize(name)
        if (requested.isBlank()) return null
        val exact = contacts.filter { normalize(it.first) == requested }
        val matches = if (exact.isNotEmpty()) exact else contacts.filter {
            normalize(it.first).split(' ').contains(requested)
        }
        val names = matches.map { normalize(it.first) }.distinct()
        if (names.size != 1) return null
        return matches.map { it.second.filter(Char::isDigit) }
            .filter { it.isNotBlank() }
            .distinct()
            .singleOrNull()
            ?.let { digits -> matches.firstOrNull { it.second.filter(Char::isDigit) == digits }?.second }
    }
}
