package com.assistant.core.services
import org.junit.Assert.*
import org.junit.Test
class SpeechChunksTest {
    @Test fun longSpeechKeepsEveryWordAndFitsTtsLimit() {
        val text = (1..1200).joinToString(" ") { "Forecast$it." }
        val chunks = SpeechChunks.split(text)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 3000 })
        assertEquals(text, chunks.joinToString(" "))
    }
    @Test fun shortAndBlankSpeech() {
        assertEquals(listOf("Hello."), SpeechChunks.split("Hello."))
        assertTrue(SpeechChunks.split(" ").isEmpty())
    }
    @Test fun fastFirstClauseDoesNotLoseWordsOrChangeLaterChunkLimits() {
        val long = (1..90).joinToString(" ") { "word$it" }
        val chunks = SpeechChunks.lowLatency(long)
        assertTrue(chunks.size > 2)
        assertTrue(chunks.first().length <= 70)
        assertTrue(chunks.drop(1).all { it.length <= 180 })
        assertEquals(long, chunks.joinToString(" "))
    }
    @Test fun shortSpokenAnswersAndBlankInputKeepOriginalBehavior() {
        assertEquals(listOf("Right here."), SpeechChunks.lowLatency("Right here."))
        assertTrue(SpeechChunks.lowLatency(" ").isEmpty())
    }
}
