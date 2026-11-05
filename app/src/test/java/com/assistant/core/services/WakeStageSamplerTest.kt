package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class WakeStageSamplerTest {
    @Test fun decoderHotPathDoesNotWriteEveryAudioBuffer() {
        val sampler = WakeStageSampler(5_000L)
        assertTrue(sampler.shouldWrite("sherpa-worker-before-decode-1", 0))
        for (n in 2..100) {
            assertFalse(sampler.shouldWrite("sherpa-worker-after-is-ready-$n-true", n * 20L))
            assertFalse(sampler.shouldWrite("sherpa-before-decode-chunk-$n", n * 20L))
        }
        assertTrue(sampler.shouldWrite("sherpa-after-decode-chunk-101", 5_101))
    }

    @Test fun importantFailureAndLifecycleEventsRemainVisible() {
        val sampler = WakeStageSampler()
        assertTrue(sampler.shouldWrite("sherpa-worker-before-read", 100))
        assertTrue(sampler.shouldWrite("sherpa-audio-read-timeout", 101))
        assertTrue(sampler.shouldWrite("sherpa-worker-failed", 102))
        assertTrue(sampler.shouldWrite("sherpa-mic-recording", 103))
        assertTrue(sampler.shouldWrite("sherpa-worker-before-read", 6_100))
    }

    @Test fun clockResetDoesNotSuppressForever() {
        val sampler = WakeStageSampler()
        assertTrue(sampler.shouldWrite("sherpa-worker-before-accept", 10_000))
        assertTrue(sampler.shouldWrite("sherpa-worker-after-accept", 20))
    }
}
