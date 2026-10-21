package com.assistant.core.services

import com.assistant.core.engine.PrivilegedAccess
import org.junit.Assert.*
import org.junit.Test

class DeviceReportTest {
    private class Platform : DeviceReport.Platform {
        val reads = mutableListOf<String>()
        var batteryValue = DeviceReport.Battery(75, 100, DeviceReport.Charging.DISCHARGING, 295)
        var networkValue = DeviceReport.Network(true, true, false, true, setOf(DeviceReport.Transport.WIFI, DeviceReport.Transport.VPN))
        var failStorage = false
        var denyNetwork = false
        var bridgesValue = PrivilegedAccess.Snapshot(PrivilegedAccess.State.NEEDS_PERMISSION, PrivilegedAccess.State.NOT_CONNECTED, false)
        private fun <T> read(name: String, value: T): DeviceReport.Reading<T> { reads += name; return DeviceReport.Reading(value) }
        override fun battery() = read("battery", batteryValue)
        override fun storage(): DeviceReport.Reading<DeviceReport.Storage> {
            reads += "storage"; if (failStorage) error("private path /secret/files and user data")
            return DeviceReport.Reading(DeviceReport.Storage(1073741824L, 4294967296L))
        }
        override fun memory() = read("memory", DeviceReport.Memory(2147483648L, 8589934592L, false))
        override fun network(): DeviceReport.Reading<DeviceReport.Network> {
            reads += "network"; if (denyNetwork) throw SecurityException("private SSID, address and token")
            return DeviceReport.Reading(networkValue)
        }
        override fun thermal() = read("thermal", DeviceReport.Thermal.LIGHT)
        override fun app() = read("app", DeviceReport.App("3.5.1", 1))
        override fun contacts() = read("contacts", false)
        override fun calls() = read("calls", false)
        override fun notifications() = read("notifications", true)
        override fun accessibility() = read("accessibility", DeviceReport.Accessibility.CONFIGURED)
        override fun bridges(): PrivilegedAccess.Snapshot { reads += "bridges"; return bridgesValue }
    }

    @Test fun supportedQuestionsStayLocalWithoutCapturingUnrelatedConversation() {
        for ((text, command) in mapOf("How's my phone doing?" to DeviceReport.Command.ALL,
            "Can you check my battery?" to DeviceReport.Command.BATTERY, "How much RAM is available?" to DeviceReport.Command.MEMORY,
            "show device capabilities" to DeviceReport.Command.ACCESS, "what version of Jarvis is installed?" to DeviceReport.Command.APP)) {
            assertEquals(text, command, DeviceReport.command(text))
            assertTrue(text, LearnedCommandReviewCommands.isReserved(text))
        }
        for (text in listOf("Why is battery life worse?", "Tell me about network protocols", "What does thermal throttling mean?", "test internet connection", "show offline capabilities"))
            assertNull(text, DeviceReport.command(text))
    }

    @Test fun malformedControlsCannotBecomeADeviceActionSearchOrLearnedAlias() {
        val platform = Platform()
        for (text in listOf("device report then reboot", "show device capabilities and grant everything",
            "network status then search the service manual", "installed Jarvis version then update app")) {
            val command = DeviceReport.command(text)
            assertEquals(text, DeviceReport.Command.INVALID, command)
            assertTrue(LearnedCommandReviewCommands.isReserved(text))
            assertTrue(DeviceReport.render(command!!, platform).contains("Nothing was changed"))
        }
        assertTrue(platform.reads.isEmpty())
    }

    @Test fun eachRequestReadsFreshValuesAndIndividualQuestionsReadOnlyTheirSection() {
        val platform = Platform()
        assertTrue(DeviceReport.render(DeviceReport.Command.BATTERY, platform).contains("75%"))
        platform.batteryValue = DeviceReport.Battery(1, 4, DeviceReport.Charging.CHARGING)
        val next = DeviceReport.render(DeviceReport.Command.BATTERY, platform)
        assertTrue(next.contains("25%")); assertTrue(next.contains("charging"))
        assertEquals(listOf("battery", "battery"), platform.reads)
        assertFalse(next.contains("battery sensor 0.0"))
    }

    @Test fun invalidOrMissingBatteryReadingsDoNotInventZeroChargeOrTemperature() {
        val platform = Platform()
        for ((level, scale) in listOf(-1 to 100, 1 to 0, 101 to 100)) {
            platform.batteryValue = DeviceReport.Battery(level, scale, DeviceReport.Charging.UNKNOWN)
            val reply = DeviceReport.render(DeviceReport.Command.BATTERY, platform)
            assertTrue(reply.contains("invalid measurement")); assertFalse(reply.contains("0%"))
        }
        platform.batteryValue = DeviceReport.Battery(0, 100, DeviceReport.Charging.UNKNOWN, Int.MIN_VALUE)
        val empty = DeviceReport.render(DeviceReport.Command.BATTERY, platform)
        assertTrue(empty.contains("0%")); assertTrue(empty.contains("temperature unavailable"))
    }

    @Test fun fullSnapshotDistinguishesStorageRamBatterySensorAndThermalStatusWithoutEnumeratingAccess() {
        val platform = Platform()
        val reply = DeviceReport.render(DeviceReport.Command.ALL, platform)
        assertTrue(reply.contains("1.0 GiB available of 4.0 GiB"))
        assertTrue(reply.contains("2.0 GiB available of 8.0 GiB"))
        assertTrue(reply.contains("battery sensor 29.5°C"))
        assertTrue(reply.contains("not a CPU temperature"))
        assertTrue(reply.contains("not a measurement of every device volume"))
        assertEquals(setOf("app", "battery", "storage", "memory", "network", "thermal"), platform.reads.toSet())
        assertFalse(platform.reads.contains("bridges"))
    }

    @Test fun oneMissingServiceOrPermissionDoesNotHideAvailableMeasurementsOrLeakRawErrors() {
        val platform = Platform().apply { failStorage = true; denyNetwork = true }
        val reply = DeviceReport.render(DeviceReport.Command.ALL, platform)
        assertTrue(reply.contains("Battery: 75%")); assertTrue(reply.contains("RAM: 2.0"))
        assertTrue(reply.contains("App data storage: unavailable")); assertTrue(reply.contains("Network: permission unavailable"))
        for (secret in listOf("/secret/files", "SSID", "token", "user data")) assertFalse(reply.contains(secret))
        assertTrue(reply.contains("No network probe was sent")); assertTrue(reply.contains("not uploaded"))
    }

    @Test fun networkCapabilityValidationAndVpnAreNotConfusedWithProviderReachability() {
        val platform = Platform()
        val unvalidated = DeviceReport.render(DeviceReport.Command.NETWORK, platform)
        assertTrue(unvalidated.contains("Wi-Fi + VPN")); assertTrue(unvalidated.contains("validation not reported"))
        assertTrue(unvalidated.contains("metered")); assertTrue(unvalidated.contains("do not verify a website or AI provider"))
        platform.networkValue = DeviceReport.Network(true, true, true, false, setOf(DeviceReport.Transport.CELLULAR))
        val validated = DeviceReport.render(DeviceReport.Command.NETWORK, platform)
        assertTrue(validated.contains("validation reported")); assertTrue(validated.contains("unmetered"))
        platform.networkValue = DeviceReport.Network(false)
        assertTrue(DeviceReport.render(DeviceReport.Command.NETWORK, platform).contains("no active default network"))
    }

    @Test fun anUnsupportedThermalApiDoesNotInvokeAnUnavailableMethodAndUnknownCodesStayUnknown() {
        var calls = 0
        assertEquals(DeviceReport.Problem.UNSUPPORTED, DeviceReport.thermal(28) { calls++; 0 }.problem)
        assertEquals(0, calls)
        assertEquals(DeviceReport.Thermal.SEVERE, DeviceReport.thermal(29) { calls++; 3 }.value)
        assertEquals(DeviceReport.Problem.INVALID, DeviceReport.thermal(34) { -1 }.problem)
        assertEquals(DeviceReport.Problem.PERMISSION, DeviceReport.thermal(34) { throw SecurityException("private") }.problem)
    }

    @Test fun capabilityReviewSeparatesPermissionConnectionContentAccessAndAuthorization() {
        val platform = Platform()
        val reply = DeviceReport.render(DeviceReport.Command.ACCESS, platform)
        assertTrue(reply.contains("service connected; grant Jarvis permission"))
        assertTrue(reply.contains("Dhizuku: not connected"))
        assertTrue(reply.contains("this process has no connected service"))
        assertTrue(reply.contains("posting permission does not provide access"))
        assertTrue(reply.contains("recipient review and explicit call approval") || reply.contains("reviewed Android dialer"))
        assertTrue(reply.contains("individual channels and delivery remain unverified"))
        assertFalse(platform.reads.any { it in setOf("battery", "storage", "memory", "network", "thermal") })
        platform.bridgesValue = PrivilegedAccess.Snapshot(PrivilegedAccess.State.AUTHORIZED, PrivilegedAccess.State.UNKNOWN, null)
        val changed = DeviceReport.render(DeviceReport.Command.ACCESS, platform)
        assertTrue(changed.contains("Shizuku: service connected and permission granted"))
        assertTrue(changed.contains("Dhizuku: readiness could not be verified"))
        assertTrue(changed.contains("Device owner: could not be verified"))
    }

    @Test fun reportModelsRejectAmbiguousReadingsAndDoNotTurnMissingDataIntoAValue() {
        assertEquals(DeviceReport.Problem.PERMISSION, DeviceReport.read<Boolean> { throw SecurityException("secret") }.problem)
        assertEquals(DeviceReport.Problem.UNAVAILABLE, DeviceReport.read<Boolean> { throw NoSuchMethodError("secret") }.problem)
        try { DeviceReport.Reading<Boolean>(); fail("A reading needs either a value or a problem") } catch (_: IllegalArgumentException) { }
        try { DeviceReport.Reading(true, DeviceReport.Problem.UNAVAILABLE); fail("A reading cannot be both present and unavailable") } catch (_: IllegalArgumentException) { }
    }
}
