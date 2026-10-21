package com.jarvis.connectionmanager

import java.net.HttpURLConnection
import java.net.URL
import android.os.SystemClock

/** Probes only public service reachability. Never treats an HTTP response as proof of API authentication. */
object ProviderDiagnostics {
    data class Result(val label: String, val detail: String, val latencyMs: Long, val reachable: Boolean)

    fun checkPublicEndpoint(label: String, endpoint: String): Result {
        val start = SystemClock.elapsedRealtime()
        return try {
            val request = URL(endpoint).openConnection() as HttpURLConnection
            request.connectTimeout = 5000
            request.readTimeout = 5000
            request.instanceFollowRedirects = false
            request.setRequestProperty("User-Agent", "JarvisConnectionManager/0.1")
            try {
                val status = request.responseCode
                val latency = SystemClock.elapsedRealtime() - start
                Result(label, "HTTP $status; authentication not tested", latency, status in 200..499)
            } finally { request.disconnect() }
        } catch (e: Exception) {
            Result(label, "Unavailable: ${e.javaClass.simpleName}", SystemClock.elapsedRealtime() - start, false)
        }
    }
}
