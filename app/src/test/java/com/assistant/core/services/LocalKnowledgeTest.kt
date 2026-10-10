package com.assistant.core.services

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class LocalKnowledgeTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-10T03:32:32Z"), ZoneId.of("America/New_York"))

    @Test fun relativeDatesUsePhoneTimezone() {
        assertEquals("Tomorrow is Saturday, October 10, 2026.", LocalKnowledge.reply("What is tomorrow's date?", clock))
        assertEquals("Yesterday was Thursday, October 8, 2026.", LocalKnowledge.reply("What was yesterday's date?", clock))
        assertEquals("Today is Friday, October 9, 2026.", LocalKnowledge.reply("What's the date?", clock))
        assertEquals("Tomorrow is Saturday.", LocalKnowledge.reply("What day is tomorrow?", clock))
    }

    @Test fun dateCrossesYearAndDaylightSavingBoundaries() {
        val yearEnd = Clock.fixed(Instant.parse("2026-12-31T17:00:00Z"), ZoneId.of("America/New_York"))
        assertEquals("Tomorrow is Friday, January 1, 2027.", LocalKnowledge.reply("Tomorrow's date", yearEnd))
        val dst = Clock.fixed(Instant.parse("2026-03-08T04:30:00Z"), ZoneId.of("America/New_York"))
        assertEquals("Tomorrow is Sunday, March 8, 2026.", LocalKnowledge.reply("Tomorrow's date", dst))
    }

    @Test fun arithmeticPreservesOperatorsAndPrecedence() {
        assertEquals("4", LocalKnowledge.reply("How much is 2 + 2?"))
        assertEquals("4", LocalKnowledge.reply("What's two plus two?"))
        assertEquals("14", LocalKnowledge.reply("Calculate 2 + 3 * 4"))
        assertEquals("20", LocalKnowledge.reply("Calculate (2 + 3) * 4"))
        assertEquals("0.3", LocalKnowledge.reply("0.1 + 0.2"))
        assertEquals("-6", LocalKnowledge.reply("-2 times 3"))
        assertEquals("50", LocalKnowledge.reply("What is 25 percent of 200?"))
        assertEquals("2.5", LocalKnowledge.reply("ten divided by four"))
    }

    @Test fun invalidMathAndOtherCommandsStaySafe() {
        assertEquals("I can't divide by zero.", LocalKnowledge.reply("2 / 0"))
        assertTrue(LocalKnowledge.reply("Calculate (2 + 3")!!.startsWith("I couldn't calculate"))
        assertNull(LocalKnowledge.reply("Create an appointment tomorrow"))
        assertNull(LocalKnowledge.reply("Call two"))
        assertNull(LocalKnowledge.reply("What is the date of my appointment tomorrow?"))
    }
}
