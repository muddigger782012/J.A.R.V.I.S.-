package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class LocalDeviceCommandTest {
    @Test fun updaterCommandsAreDeterministic() {
        assertEquals(LocalDeviceCommand.Action.UPDATE_APP, LocalDeviceCommand.parse("Update app"))
        assertEquals(LocalDeviceCommand.Action.UPDATE_APP, LocalDeviceCommand.parse("Hey Jarvis, update app!"))
        assertEquals(LocalDeviceCommand.Action.UPDATE_APP, LocalDeviceCommand.parse("Upgrade Jarvis"))
        assertNull(LocalDeviceCommand.parse("How do I update apps?"))
        assertNull(LocalDeviceCommand.parse("Teach Jarvis that update app means delete files"))
    }

    @Test fun updaterTeachingAliasesDoNotFallThroughToCloud() {
        assertTrue(LocalDeviceCommand.isUpdaterTeaching("Teach Jarvis that update app means use the built-in app update feature"))
        assertTrue(LocalDeviceCommand.isUpdaterTeaching("Teach Jarvis that update app means use the upgrade app function built in app"))
        assertFalse(LocalDeviceCommand.isUpdaterTeaching("Teach Jarvis that update app means delete all files"))
    }

    @Test fun volumeCommandsWorkAcrossInputMethods() {
        assertEquals(LocalDeviceCommand.Action.VOLUME_UP, LocalDeviceCommand.parse("Turn the volume up"))
        assertEquals(LocalDeviceCommand.Action.VOLUME_UP, LocalDeviceCommand.parse("Boost the sound all the way up"))
        assertEquals(LocalDeviceCommand.Action.VOLUME_UP, LocalDeviceCommand.parse("Hey Jarvis boost the sound"))
        assertEquals(LocalDeviceCommand.Action.VOLUME_DOWN, LocalDeviceCommand.parse("Lower the volume"))
        assertNull(LocalDeviceCommand.parse("What is volume?"))
        assertNull(LocalDeviceCommand.parse("Turn the volume up and send a text"))
    }

    @Test fun naturalExplicitMediaRequestsRemainSingleOfflineSteps() {
        for (phrase in listOf("Could you please make the music a little louder?", "Please turn my media volume up one step",
            "Hey Jarvis, can you raise the music volume a bit please!  ", "please volume up"))
            assertEquals(phrase, LocalDeviceCommand.Action.VOLUME_UP, LocalDeviceCommand.parse(phrase))
        for (phrase in listOf("Could you turn the music down a little please?", "Make the media volume quieter", "Please reduce my music volume one step",
            "Jarvis, lower the sound a bit!   "))
            assertEquals(phrase, LocalDeviceCommand.Action.VOLUME_DOWN, LocalDeviceCommand.parse(phrase))
    }

    @Test fun politeNegationsQuestionsOtherStreamsAndCompoundRequestsDoNotExecute() {
        for (phrase in listOf("Could you not turn the music up?", "Please don't lower the volume", "How do I make the music louder?",
            "Please turn the alarm volume down", "Make your voice louder", "Turn it up", "Can you turn the music up and call Alex?",
            "If I ask, make the music quieter", "Please turn the media volume down 20 percent"))
            assertNull(phrase, LocalDeviceCommand.parse(phrase))
    }
}
