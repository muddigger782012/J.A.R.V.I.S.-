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
                val detail = when (status) {
                    in 200..299 -> "REACHABLE (HTTP $status); auth not tested"
                    in 300..399 -> "REDIRECT (HTTP $status); auth not tested"
                    401, 403 -> "ACCESS RESTRICTED (HTTP $status); auth not tested"
                    404 -> "ENDPOINT NOT FOUND (HTTP 404)"
                    429 -> "RATE LIMITED (HTTP 429)"
                    in 400..499 -> "REQUEST REJECTED (HTTP $status)"
                    in 500..599 -> "SERVER ERROR (HTTP $status)"
                    else -> "UNEXPECTED HTTP $status"
                }
                Result(label, detail, latency, status in 200..399)
            } finally { request.disconnect() }
        } catch (e: Exception) {
            Result(label, "Unavailable: ${e.javaClass.simpleName}", SystemClock.elapsedRealtime() - start, false)
        }
    }
}
