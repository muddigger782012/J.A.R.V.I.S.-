package com.assistant.core.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class WeatherLocation internal constructor(private val device: Device) {
    constructor(context: Context) : this(AndroidDevice(context))

    internal interface Device {
        fun precisePermission(): Boolean
        fun providers(): List<String>
        fun start(provider: String, signal: CancellationSignal, callback: (Location?) -> Unit)
    }
    private class AndroidDevice(private val context: Context) : Device {
        private val manager by lazy { context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager }
        override fun precisePermission() = ContextCompat.checkSelfPermission(context,
            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        override fun providers() = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
            runCatching { manager?.isProviderEnabled(it) == true }.getOrDefault(false)
        }
        override fun start(provider: String, signal: CancellationSignal, callback: (Location?) -> Unit) {
            val manager = manager ?: run { callback(null); return }
            // Permission can be revoked after the initial check, including between these lines.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                callback(null); return
            }
            try {
                LocationManagerCompat.getCurrentLocation(manager, provider, signal,
                    java.util.concurrent.Executor { it.run() }, callback)
            } catch (_: SecurityException) { callback(null) }
        }
    }
    fun hasPrecisePermission(): Boolean = device.precisePermission()

    /** Called from the online worker; requests are cancelled after success or timeout. */
    fun current(): Location? {
        if (!hasPrecisePermission()) return null
        val providers = device.providers()
        if (providers.isEmpty()) return null
        val result = AtomicReference<Location?>(null)
        val latch = CountDownLatch(1)
        val signals = providers.map { CancellationSignal() }
        val finished = providers.map { AtomicBoolean(false) }
        val completed = AtomicInteger(0)
        fun finish(index: Int, location: Location?) {
            if (!finished[index].compareAndSet(false, true)) return
            if (location != null && location.hasAccuracy() && location.accuracy <= 100f &&
                android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos in 0..TimeUnit.MINUTES.toNanos(2)) {
                if (result.compareAndSet(null, location)) latch.countDown()
            }
            if (completed.incrementAndGet() == providers.size) latch.countDown()
        }
        try {
            providers.forEachIndexed { index, provider ->
                try { device.start(provider, signals[index]) { finish(index, it) } }
                catch (_: SecurityException) { finish(index, null) }
                catch (_: Exception) { finish(index, null) }
            }
            latch.await(20, TimeUnit.SECONDS)
            return if (hasPrecisePermission()) result.get() else null
        } finally { signals.forEach { runCatching { it.cancel() } } }
    }
}
