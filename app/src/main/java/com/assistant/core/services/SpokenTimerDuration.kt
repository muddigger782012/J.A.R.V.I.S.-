package com.assistant.core.services

import java.util.Locale

/** Strict, local English duration grammar. Parsing never authorizes or runs an action. */
object SpokenTimerDuration {
    private val small = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val tens = listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private fun clean(text: String) = text.trim().trimEnd('.', '?', '!').trim().lowercase(Locale.ROOT)
        .replace(Regex("(?<=[a-z])-(?=[a-z])"), " ").replace(Regex("\\s+"), " ")

    fun command(text: String): Int? {
        val match = Regex("^(?:set|start) (?:a )?timer (?:for )?(.+)$").matchEntire(clean(text)) ?: return null
        return seconds(match.groupValues[1])
    }
    fun seconds(text: String): Int? {
        var remaining = clean(text)
        var previous = Int.MAX_VALUE
        var total = 0L
        var count = 0
        while (remaining.isNotEmpty()) {
            if (++count > 3) return null
            val match = Regex("^(.+?) (hours?|minutes?|seconds?)(?= |$)").find(remaining) ?: return null
            val amount = number(match.groupValues[1]) ?: return null
            val unit = match.groupValues[2]
            val multiplier = when { unit.startsWith("hour") -> 3600; unit.startsWith("minute") -> 60; else -> 1 }
            // Descending, non-repeated units prevent ambiguous interpretations.
            if (multiplier >= previous) return null
            previous = multiplier
            total += amount.toLong() * multiplier
            if (total > 86400L) return null
            remaining = remaining.substring(match.range.last + 1).trim()
            if (remaining.startsWith("and ")) remaining = remaining.removePrefix("and ")
            else if (remaining == "and") return null
        }
        return total.toInt().takeIf { it in 1..86400 }
    }
    private fun number(text: String): Int? {
        if (Regex("[0-9]{1,6}").matches(text)) return text.toIntOrNull()
        if (text == "a" || text == "an") return 1
        val words = text.split(' ')
        val thousand = words.indexOf("thousand")
        if (thousand < 0) return underThousand(words)
        if (words.count { it == "thousand" } != 1) return null
        val high = underThousand(words.take(thousand))?.takeIf { it > 0 } ?: return null
        var rest = words.drop(thousand + 1)
        if (rest.firstOrNull() == "and") { rest = rest.drop(1); if (rest.isEmpty()) return null }
        val low = if (rest.isEmpty()) 0 else underThousand(rest) ?: return null
        return high * 1000 + low
    }
    private fun underThousand(words: List<String>): Int? {
        if (words.isEmpty()) return null
        val hundred = words.indexOf("hundred")
        if (hundred < 0) return underHundred(words)
        if (hundred != 1 || words.count { it == "hundred" } != 1) return null
        val high = small.indexOf(words[0]).takeIf { it in 1..9 } ?: return null
        var rest = words.drop(2)
        if (rest.firstOrNull() == "and") { rest = rest.drop(1); if (rest.isEmpty()) return null }
        val low = if (rest.isEmpty()) 0 else underHundred(rest)?.takeIf { it > 0 } ?: return null
        return high * 100 + low
    }
    private fun underHundred(words: List<String>): Int? {
        if (words.size == 1) {
            val value = small.indexOf(words[0])
            if (value >= 0) return value
        }
        if (words.size !in 1..2) return null
        val high = tens.indexOf(words[0]).takeIf { it >= 0 } ?: return null
        val low = if (words.size == 1) 0 else small.indexOf(words[1]).takeIf { it in 1..9 } ?: return null
        return (high + 2) * 10 + low
    }
}
