package com.assistant.core.services

import java.math.BigDecimal
import java.math.MathContext
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure local answers. Never evaluates code or changes device state. */
object LocalKnowledge {
    fun reply(raw: String, clock: Clock = Clock.systemDefaultZone()): String? {
        val text = raw.lowercase(Locale.ROOT).replace("’", "'").trim().trimEnd('?', '!')
        val dateQuery = text.replace("'s", "s").replace("'", "")
        // Anchor the entire request so calendar creation and reminders retain their routing.
        if (Regex("(?:what(?:s| is| was| will be)? |tell me |give me )?(?:the )?(?:(?:today|tomorrow|yesterday)(?:s)? )?(?:date|day)(?: is it| is today| is tomorrow| was yesterday| today| tomorrow| yesterday| next week)?").matches(dateQuery) ||
            Regex("what (?:date|day) (?:is|was|will be) (?:it )?(?:today|tomorrow|yesterday|next week)").matches(dateQuery)) {
            val (offset, label) = when {
                "tomorrow" in dateQuery -> 1L to "Tomorrow is"
                "yesterday" in dateQuery -> -1L to "Yesterday was"
                "next week" in dateQuery -> 7L to "One week from today is"
                else -> 0L to "Today is"
            }
            val pattern = if ("date" in dateQuery) "EEEE, MMMM d, yyyy" else "EEEE"
            return "$label ${LocalDate.now(clock).plusDays(offset).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))}."
        }
        var expression = text.replace(Regex("^(?:how much is|what is|what's|whats|calculate|compute|evaluate)\\s+"), "")
            .removeSuffix(" equals").removeSuffix(" =").trim()
        val replacements = linkedMapOf("multiplied by" to "*", "divided by" to "/", "percent of" to "%*", "times" to "*", "plus" to "+", "minus" to "-", "over" to "/", "percent" to "%", "open parenthesis" to "(", "close parenthesis" to ")")
        for ((word, symbol) in replacements) expression = expression.replace(Regex("\\b$word\\b"), symbol)
        val numbers = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty")
        numbers.forEachIndexed { number, word -> expression = expression.replace(Regex("\\b$word\\b"), number.toString()) }
        expression = expression.replace('×', '*').replace('÷', '/').replace('−', '-')
        if (expression.length > 512 || !expression.any(Char::isDigit) || !expression.any { it in "+-*/%" } || !Regex("[0-9.\\s()+*/%\\-]+").matches(expression)) return null
        return try {
            Parser(expression).parse().stripTrailingZeros().toPlainString()
        } catch (e: ArithmeticException) {
            "I can't divide by zero."
        } catch (e: IllegalArgumentException) {
            "I couldn't calculate that expression. Check the numbers and parentheses."
        }
    }

    private class Parser(private val source: String) {
        private var position = 0
        private val context = MathContext.DECIMAL128
        fun parse(): BigDecimal {
            val value = sum()
            require(positionAfterSpaces() == source.length)
            return value
        }
        private fun positionAfterSpaces(): Int {
            while (position < source.length && source[position].isWhitespace()) position++
            return position
        }
        private fun take(c: Char): Boolean {
            positionAfterSpaces()
            if (position < source.length && source[position] == c) { position++; return true }
            return false
        }
        private fun sum(): BigDecimal {
            var value = product()
            while (true) value = when {
                take('+') -> value.add(product(), context)
                take('-') -> value.subtract(product(), context)
                else -> return value
            }
        }
        private fun product(): BigDecimal {
            var value = unary()
            while (true) value = when {
                take('*') -> value.multiply(unary(), context)
                take('/') -> value.divide(unary(), context)
                else -> return value
            }
        }
        private fun unary(): BigDecimal {
            if (take('+')) return unary()
            if (take('-')) return unary().negate()
            var value = if (take('(')) {
                val inner = sum()
                require(take(')'))
                inner
            } else {
                val start = positionAfterSpaces()
                while (position < source.length && (source[position].isDigit() || source[position] == '.')) position++
                require(position > start)
                source.substring(start, position).toBigDecimal()
            }
            while (take('%')) value = value.divide(BigDecimal(100), context)
            return value
        }
    }
}
