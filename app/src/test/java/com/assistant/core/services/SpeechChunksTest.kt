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
}
