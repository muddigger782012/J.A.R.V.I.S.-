package com.assistant.core.services

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/** Checks the Gemini models endpoint without requesting a generated answer. */
internal object GeminiProviderDiagnostic {
    fun check(context: Context): String {
        val apiKey = ApiKeyStore(context).read()
        if (apiKey.isBlank()) return "Gemini is not configured. No request was sent."
        return try {
            val conn = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 6000
                conn.readTimeout = 8000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("x-goog-api-key", apiKey)
                when (val status = conn.responseCode) {
                    in 200..299 -> "Gemini models endpoint responded (HTTP $status). Generation is not tested."
                    401, 403 -> "Gemini access denied (HTTP $status). Check credentials and restrictions."
                    429 -> "Gemini rate limit or quota response (HTTP 429)."
                    else -> "Gemini models endpoint returned HTTP $status. Generation is not tested."
                }
            } finally { conn.disconnect() }
        } catch (_: Exception) {
            "Gemini diagnostic failed or timed out. Provider availability is unverified."
        }
    }
}
