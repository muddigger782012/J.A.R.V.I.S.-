package com.assistant.core.services

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.util.Locale
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.json.JSONException

/** Exception classes only; exception messages can contain credentials or response data. */
enum class GeminiFailureKind(val diagnostic: String, val explanation: String) {
    DNS("DNS lookup failure", "The device couldn't resolve Gemini's server address. Check network or DNS settings and retry; this doesn't establish a Gemini outage."),
    TIMEOUT("request timeout", "The Gemini request timed out. This alone doesn't identify whether the delay came from the network path or the service. Try again shortly."),
    TLS("secure connection failure", "A secure connection to Gemini could not be established. Check the device date and time or network filtering, then retry."),
    CONNECTION("connection/response transfer failure", "The Gemini connection or response transfer failed. This can occur on the network path or at the service; it doesn't establish that the device is offline. Try again."),
    INVALID_RESPONSE("invalid response format", "A response was received, but the app couldn't read its JSON. This is a response-format failure, not evidence that the device is offline. Retry; if it repeats, check AI settings."),
    UNKNOWN("connection/response failure", "A request or response-reading failure occurred without a usable HTTP error. I can't tell from this record whether the cause was your connection, a timeout or the provider. Check internet access and retry. If other online apps work, check Gemini service availability.");

    companion object {
        fun from(error: Exception): GeminiFailureKind = when (error) {
            is UnknownHostException -> DNS
            is SocketTimeoutException -> TIMEOUT
            is SSLException -> TLS
            is JSONException -> INVALID_RESPONSE
            is IOException -> CONNECTION
            else -> UNKNOWN
        }

        fun fromStored(value: String): GeminiFailureKind = values().firstOrNull { it.name == value } ?: UNKNOWN
    }
}

/** Last failure metadata only: no prompts, keys, URLs or raw provider messages. */
class GeminiFailureMemory internal constructor(private val read: () -> String?, private val write: (String) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis) {
    constructor(context: Context) : this(
        { context.applicationContext.getSharedPreferences("jarvis_gemini_failure", Context.MODE_PRIVATE).getString("last", null) },
        { context.applicationContext.getSharedPreferences("jarvis_gemini_failure", Context.MODE_PRIVATE).edit().putString("last", it).commit() })

    fun shouldAnswer(text: String): Boolean = isQuestion(text) && (!isFollowup(text) || synchronized(lock) {
        runCatching {
            val json = JSONObject(read() ?: return@runCatching false)
            val code = json.optInt("code", -1)
            (code == 0 || code in 400..599) && json.has("created") && now() - json.getLong("created") in 0..600_000L
        }.getOrDefault(false)
    })

    fun record(code: Int, response: JSONObject?, category: GeminiFailureKind? = null): Boolean = synchronized(lock) {
        if (code != 0 && code !in 400..599) return@synchronized false
        val error = response?.optJSONObject("error")
        val status = token(error?.optString("status").orEmpty())
        val details = error?.optJSONArray("details")
        val reasons = if (details == null) emptyList() else (0 until minOf(details.length(), 8))
            .mapNotNull { token(details.optJSONObject(it)?.optString("reason").orEmpty()).takeIf(String::isNotEmpty) }.distinct()
        runCatching { write(JSONObject().put("code", code).put("status", status).put("reasons", JSONArray(reasons))
            .put("category", if (code == 0) category?.name else null).put("created", now()).toString()) }.getOrDefault(false)
    }

    fun explain(): String = synchronized(lock) {
        val json = runCatching { JSONObject(read() ?: return@synchronized NO_RECORD) }.getOrNull() ?: return@synchronized NO_RECORD
        val code = json.optInt("code", -1)
        if ((code != 0 && code !in 400..599) || json.optLong("created", -1) < 0) return@synchronized NO_RECORD
        val status = token(json.optString("status"))
        val array = json.optJSONArray("reasons") ?: JSONArray()
        val reasons = (0 until minOf(array.length(), 8)).mapNotNull { token(array.optString(it)).takeIf(String::isNotEmpty) }
        val category = GeminiFailureKind.fromStored(json.optString("category"))
        val why = when {
            code == 0 -> category.explanation
            "API_KEY_INVALID" in reasons || code == 401 -> "Authentication failed. Check the saved Gemini API key in Weather and AI settings."
            "SERVICE_DISABLED" in reasons || "API_KEY_SERVICE_BLOCKED" in reasons -> "Gemini API access or key restrictions blocked the request. Check the project and key restrictions in Google AI Studio."
            code == 503 -> "Gemini was temporarily unavailable or overloaded. This is a service failure, not evidence that your wording or content was refused. Wait briefly and retry."
            code == 429 -> "A quota or rate limit was reached. Check your Gemini API quota and retry after waiting."
            code == 403 -> "Access was denied. Key permissions, project access or regional restrictions may be involved; this code alone doesn't establish which."
            code == 404 -> "The requested model or endpoint was not found. Check the model configured in Weather and AI settings."
            code == 400 -> "Gemini considered the request invalid. Check its model and request configuration; the recorded code doesn't identify a more specific cause."
            code >= 500 -> "Gemini returned a server-side failure. Retry later; the stored metadata doesn't establish the exact internal cause."
            else -> "Gemini returned an HTTP error. I don't have enough recorded detail to identify its cause."
        }
        val diagnostic = if (code == 0) category.diagnostic else (listOf("HTTP $code") + listOf(status).filter { it.isNotEmpty() } + reasons).distinct().joinToString(", ")
        "Last recorded Gemini failure: $diagnostic.\n$why\nThis is a local explanation of the last failure, not a live outage check. It doesn't establish whether live chat is active."
    }

    companion object {
        private val lock = Any()
        private const val NO_RECORD = "No Gemini failure details are recorded on this device yet. This update records future failures; I can't reconstruct an earlier error from the chat."
        private fun token(value: String) = value.takeIf { Regex("[A-Z_]{1,80}").matches(it) }.orEmpty()
        private fun questionKey(text: String) = text.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!').replace(Regex("\\s+"), " ")
            .removePrefix("hey jarvis ").removePrefix("hey jarvis, ")
        private fun isFollowup(text: String) = questionKey(text) in setOf("what can i do to fix it", "what can i do to fix this", "how do i fix it",
            "is there anything i can do on my end to fix it", "is there anything i can do on my end", "can i fix it on my end")
        fun isQuestion(text: String): Boolean {
            val key = questionKey(text)
            if (isFollowup(text)) return true
            return key in setOf("why did gemini reject the request", "why did gemini reject that request", "why did gemini reject my request",
                "why did gemini fail", "why did gemini fail the request", "explain the last gemini error", "what was the last gemini error", "show last gemini error",
                "how do i fix the gemini error", "how can i fix the gemini error", "how do i fix that gemini error", "how do i fix the last gemini error",
                "how do i fix the error", "how can i fix this error")
        }
    }
}

