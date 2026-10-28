package com.assistant.core.engine

import org.junit.Assert.*
import org.junit.Test

class PrivilegedAccessTest {
    @Test fun aConnectedServiceWithoutPermissionCannotAdvertiseAnExecutableBridge() {
        val state = PrivilegedAccess.probe({ true }, { false })
        assertEquals(PrivilegedAccess.State.NEEDS_PERMISSION, state)
        val capabilities = PrivilegedAccess.Snapshot(state, state, false).capabilities()
        assertFalse(capabilities.shizuku); assertFalse(capabilities.dhizuku)
        assertFalse(capabilities.deviceOwner); assertTrue(capabilities.standard)
        assertFalse(capabilities.specialAccess); assertFalse(capabilities.oemPrivileged); assertFalse(capabilities.platformSigned)
    }
    @Test fun anUnavailableServiceDoesNotQueryPermissionOrRetainAnEarlierGrant() {
        var connected = true
        var checks = 0
        fun probe() = PrivilegedAccess.probe({ connected }, { checks++; true })
        assertEquals(PrivilegedAccess.State.AUTHORIZED, probe())
        connected = false
        assertEquals(PrivilegedAccess.State.NOT_CONNECTED, probe())
        assertEquals(1, checks)
    }
    @Test fun probeExceptionsFailClosedAndPermissionRevocationIsFresh() {
        var granted = true
        fun probe() = PrivilegedAccess.probe({ true }, { granted })
        assertEquals(PrivilegedAccess.State.AUTHORIZED, probe())
        granted = false
        assertEquals(PrivilegedAccess.State.NEEDS_PERMISSION, probe())
        assertEquals(PrivilegedAccess.State.UNKNOWN, PrivilegedAccess.probe({ error("Private binder error") }, { true }))
        assertEquals(PrivilegedAccess.State.UNKNOWN, PrivilegedAccess.probe({ true }, { throw SecurityException("Private permission error") }))
        val state = PrivilegedAccess.Snapshot(PrivilegedAccess.State.UNKNOWN, PrivilegedAccess.State.UNKNOWN, null).capabilities()
        assertFalse(state.shizuku); assertFalse(state.dhizuku); assertFalse(state.deviceOwner)
    }
    @Test fun directDeviceOwnershipAndAuthorizedBridgesStayDistinctFromPlaceholders() {
        val state = PrivilegedAccess.Snapshot(PrivilegedAccess.State.AUTHORIZED, PrivilegedAccess.State.NOT_CONNECTED, true).capabilities()
        assertTrue(state.shizuku); assertFalse(state.dhizuku); assertTrue(state.deviceOwner)
        assertFalse(state.specialAccess)
    }
}
