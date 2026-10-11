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

    @Test fun volumeCommandsWorkAcrossInputMethods() {
        assertEquals(LocalDeviceCommand.Action.VOLUME_UP, LocalDeviceCommand.parse("Turn the volume up"))
        assertEquals(LocalDeviceCommand.Action.VOLUME_UP, LocalDeviceCommand.parse("Boost the sound all the way up"))
        assertEquals(LocalDeviceCommand.Action.VOLUME_UP, LocalDeviceCommand.parse("Hey Jarvis boost the sound"))
        assertEquals(LocalDeviceCommand.Action.VOLUME_DOWN, LocalDeviceCommand.parse("Lower the volume"))
        assertNull(LocalDeviceCommand.parse("What is volume?"))
        assertNull(LocalDeviceCommand.parse("Turn the volume up and send a text"))
    }
}
