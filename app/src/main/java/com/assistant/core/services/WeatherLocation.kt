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

class WeatherLocation(private val context: Context) {
    fun hasPrecisePermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Called from the online worker; requests are cancelled after success or timeout. */
    fun current(): Location? {
        if (!hasPrecisePermission()) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
            runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }
        if (providers.isEmpty()) return null
        val result = AtomicReference<Location?>(null)
        val latch = CountDownLatch(1)
        val signals = providers.map { CancellationSignal() }
        try {
            providers.forEachIndexed { index, provider ->
                runCatching {
                    LocationManagerCompat.getCurrentLocation(manager, provider, signals[index], java.util.concurrent.Executor { it.run() }) { location ->
                        if (location != null && location.hasAccuracy() && location.accuracy <= 100f &&
                            android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos in 0..TimeUnit.MINUTES.toNanos(2)) {
                            if (result.compareAndSet(null, location)) latch.countDown()
                        }
                    }
                }
            }
            latch.await(20, TimeUnit.SECONDS)
            return result.get()
        } finally { signals.forEach { it.cancel() } }
    }
}
