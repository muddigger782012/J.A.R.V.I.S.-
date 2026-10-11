package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class RoutineCommandParserTest {
    @Test fun parsesOnlyKnownMultiStepActions() {
        val result = RoutineCommandParser.parse("Teach Jarvis that prepare audio means volume up and update app")
        assertEquals("prepare audio", result?.phrase)
        assertEquals(listOf("volume_up", "app_update"), result?.actions)
    }

    @Test fun rejectsUnsupportedAndSensitiveActions() {
        assertNull(RoutineCommandParser.parse("Teach Jarvis that prepare means volume up and delete files"))
        assertNull(RoutineCommandParser.parse("Teach Jarvis that prepare means volume up and volume up"))
        assertNull(RoutineCommandParser.parse("Teach Jarvis that update app means volume up and volume down"))
        assertNull(RoutineCommandParser.parse("Teach Jarvis that prepare means send a text"))
        assertNull(RoutineCommandParser.parse("Teach Jarvis that prepare means volume up and send a text"))
    }

    @Test fun understandsWakeWordPunctuationInSteps() {
        assertEquals(listOf("volume_down", "app_update"),
            RoutineCommandParser.parse("Teach Jarvis that finish work means volume down and update app!")?.actions)
    }
}
