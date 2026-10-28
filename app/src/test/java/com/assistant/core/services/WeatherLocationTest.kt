package com.assistant.core.services

import android.location.Location
import androidx.core.os.CancellationSignal
import org.junit.Assert.*
import org.junit.Test

class WeatherLocationTest {
    private class Device(private val granted: Boolean, private val deniedOnStart: Boolean) : WeatherLocation.Device {
        val signals = mutableListOf<CancellationSignal>()
        override fun precisePermission() = granted
        override fun providers() = listOf("gps", "network")
        override fun start(provider: String, signal: CancellationSignal, callback: (Location?) -> Unit) {
            signals.add(signal)
            if (deniedOnStart) throw SecurityException("Fixture permission changed")
            callback(null)
            callback(null) // Misbehaving duplicates cannot complete another provider.
        }
    }
    @Test(timeout = 2000) fun deniedPermissionNeverStartsLocationCapture() {
        val device = Device(false, true)
        assertNull(WeatherLocation(device).current()); assertTrue(device.signals.isEmpty())
    }
    @Test(timeout = 2000) fun permissionRevokedBeforeBothRequestsDoesNotWaitForTheTwentySecondTimeout() {
        val device = Device(true, true)
        assertNull(WeatherLocation(device).current())
        assertEquals(2, device.signals.size); assertTrue(device.signals.all { it.isCanceled })
    }
    @Test(timeout = 2000) fun completedUnavailableProvidersReturnPromptlyAndCancelEveryRequest() {
        val device = Device(true, false)
        assertNull(WeatherLocation(device).current())
        assertEquals(2, device.signals.size); assertTrue(device.signals.all { it.isCanceled })
    }
}
