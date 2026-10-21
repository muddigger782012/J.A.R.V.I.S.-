package com.jarvis.connectionmanager

import java.net.HttpURLConnection
import java.net.URL
import android.os.SystemClock

/** Safe, explicit authenticated Gemini model-list probe. Call only after user configures a key. */
object GeminiDiagnostics {
    data class Result(val detail: String, val latencyMs: Long, val authenticated: Boolean)

    fun checkModels(apiKey: String): Result {
        if (apiKey.isBlank()) return Result("NOT CONFIGURED", 0, false)
        val started = SystemClock.elapsedRealtime()
        return try {
            val request = URL("https://generativelanguage.googleapis.com/v1beta/models").openConnection() as HttpURLConnection
            request.connectTimeout = 7000
            request.readTimeout = 7000
            request.setRequestProperty("x-goog-api-key", apiKey)
            request.setRequestProperty("Accept", "application/json")
            try {
                val code = request.responseCode
                val description = when (code) {
                    200 -> "AUTHENTICATED"
                    400, 401, 403 -> "CREDENTIALS REJECTED (HTTP $code)"
                    429 -> "RATE LIMITED"
                    in 500..599 -> "PROVIDER ERROR (HTTP $code)"
                    else -> "HTTP $code"
                }
                Result(description, SystemClock.elapsedRealtime() - started, code == 200)
            } finally { request.disconnect() }
        } catch (e: Exception) {
            Result("UNAVAILABLE: ${e.javaClass.simpleName}", SystemClock.elapsedRealtime() - started, false)
        }
    }
}
