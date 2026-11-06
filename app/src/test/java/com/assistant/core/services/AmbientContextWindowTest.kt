package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class AmbientContextWindowTest {
    @Test fun passiveSpeechIsRecalledOnlyWhenExplicitlyAsked() {
        var clock = 0L
        val window = AmbientContextWindow { clock }
        window.observe("The fuel pressure keeps dropping when I raise the RPM", true)
        clock += 1000
        window.observe("Maybe it is the regulator", true)
        val result = window.recall("Jarvis, what were we just talking about?", true)!!
        assertTrue(result.contains("fuel pressure"))
        assertTrue(result.contains("regulator"))
        assertTrue(result.contains("can't reliably tell who"))
        assertNull(window.recall("Jarvis, turn on the lights", true))
    }

    @Test fun contextNeverPersistsBeyondNinetySecondsOrNewSession() {
        var clock = 1000L
        val window = AmbientContextWindow { clock }
        window.observe("The engine is surging at high idle", true)
        clock += AmbientContextWindow.MAX_AGE_MILLIS
        assertFalse(window.recall("Jarvis, what were we talking about?", true)!!.contains("surging"))
        window.observe("Check the injection return pressure", true)
        window.clear()
        assertFalse(window.recall("Jarvis, what were we talking about?", true)!!.contains("injection"))
    }

    @Test fun disablingContextClearsExistingRoomMemory() {
        val window = AmbientContextWindow { 12L }
        window.observe("Someone was discussing the brakes", true)
        assertEquals("Ambient conversation context is turned off in Settings.",
            window.recall("Jarvis what did you hear?", false))
        assertFalse(window.recall("Jarvis what did you hear?", true)!!.contains("brakes"))
    }

    @Test fun passiveWindowHasStrictTextAndCountBounds() {
        val window = AmbientContextWindow { 9L }
        repeat(10) { n -> window.observe("Topic $n " + "x".repeat(300), true) }
        val reply = window.recall("Jarvis, catch me up", true)!!
        assertFalse(reply.contains("Topic 0"))
        assertFalse(reply.contains("Topic 6"))
        assertTrue(reply.contains("Topic 9"))
        assertTrue(reply.length <= 3 * AmbientContextWindow.MAX_OBSERVATION_CHARS + 130)
    }

    @Test fun offAndControlPhrasesCannotBeStoredAsAmbientContext() {
        val window = AmbientContextWindow { 1L }
        window.observe("stop live chat", true)
        window.observe("start live chat", true)
        window.observe("The valve temperature is rising", false)
        assertFalse(window.recall("Jarvis what did you hear?", true)!!.contains("live chat"))
        window.observe("The valve temperature is rising", true)
        assertTrue(window.recall("Jarvis what did you hear?", true)!!.contains("valve"))
    }
}
