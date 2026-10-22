package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class ConnectionAssistanceTest {
    private class Phone : ConnectionAssistance.Platform {
        var writes = 0; var opened: ConnectionAssistance.Kind? = null; var managerReads = 0
        override fun wifi() = ConnectionAssistance.State.ON
        override fun bluetooth(): ConnectionAssistance.State = throw SecurityException()
        override fun cellular() = ConnectionAssistance.State.OFF
        override fun vpn() = ConnectionAssistance.State.ON
        override fun network() = "Default network: Android validated"
        override fun settings(kind: ConnectionAssistance.Kind): Boolean { writes++; opened = kind; return true }
        override fun manager(): String { managerReads++; return "Review launcher choices" }
    }
    @Test fun localDiagnosticsDistinguishRadiosDataAndVpnWithoutChangingAnything() {
        val phone = Phone(); val result = ConnectionAssistance.reply(ConnectionAssistance.Kind.ALL, phone)
        assertTrue(result.contains("radio enabled")); assertTrue(result.contains("permission unavailable"))
        assertTrue(result.contains("data disconnected")); assertTrue(result.contains("tunnel health"))
        assertEquals(0, phone.writes); assertEquals(0, phone.managerReads)
    }
    @Test fun requestedRadioAndVpnChangesOnlyOpenUserOperatedSettings() {
        val phone = Phone()
        for (phrase in listOf("turn off wifi", "turn on bluetooth", "turn off mobile data", "disconnect vpn")) {
            val reply = ConnectionAssistance.reply(ConnectionAssistance.parse(phrase)!!, phone)
            assertTrue(reply, reply.contains("approve any change there"))
            assertTrue(reply, reply.contains("has not changed"))
        }
        assertEquals(4, phone.writes)
    }
    @Test fun settingsFailureNeverClaimsAChangeAndUnsupportedTargetsNeverOpenAnything() {
        val phone = object : ConnectionAssistance.Platform by Phone() {
            override fun settings(kind: ConnectionAssistance.Kind): Boolean = throw SecurityException()
        }
        val result = ConnectionAssistance.reply(ConnectionAssistance.Kind.VPN_SETTINGS, phone)
        assertTrue(result.contains("could not be opened")); assertTrue(result.contains("no change"))
        assertNull(AndroidConnectionAssistance.settingsAction(ConnectionAssistance.Kind.MANAGER))
        assertNull(AndroidConnectionAssistance.settingsAction(ConnectionAssistance.Kind.ALL))
    }
    @Test fun managerHandoffDoesNotPretendToHaveAnUndocumentedRemoteInterface() {
        val phone = Phone(); val result = ConnectionAssistance.reply(ConnectionAssistance.parse("open Jarvis Connection Manager")!!, phone)
        assertEquals("Review launcher choices", result); assertEquals(1, phone.managerReads); assertEquals(0, phone.writes)
    }
    @Test fun chainedCommandsAreNotGrantedAnActionByTheConnectionGrammar() {
        val phone = Phone()
        assertEquals(ConnectionAssistance.Kind.INVALID, ConnectionAssistance.parse("vpn status then delete files"))
        assertNull(ConnectionAssistance.parse("open vpn settings then buy a subscription"))
        ConnectionAssistance.reply(ConnectionAssistance.Kind.INVALID, phone)
        assertEquals(0, phone.writes)
    }
}
