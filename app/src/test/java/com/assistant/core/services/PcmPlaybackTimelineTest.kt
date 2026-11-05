package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class PcmPlaybackTimelineTest {
    @Test fun allChunksShareOneMonotonicPlaybackPosition() {
        val timeline = PcmPlaybackTimeline()
        timeline.accept(24_000, 2_048)
        timeline.accept(24_000, 3_072)
        timeline.accept(24_000, 2_048)
        assertEquals(24_000, timeline.sampleRate)
        assertEquals(7_168L, timeline.framesQueued)
        assertFalse(timeline.isDrained(2_048))
        assertFalse(timeline.isDrained(7_167))
        assertTrue(timeline.isDrained(7_168))
    }

    @Test fun mismatchedVoiceChunkRateIsNotSilentlyReplayed() {
        val timeline = PcmPlaybackTimeline()
        timeline.accept(24_000, 1_000)
        assertThrows(IllegalArgumentException::class.java) { timeline.accept(16_000, 100) }
        assertEquals(1_000L, timeline.framesQueued)
    }
}
