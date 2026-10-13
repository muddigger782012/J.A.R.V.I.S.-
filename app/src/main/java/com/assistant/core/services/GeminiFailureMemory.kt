package com.assistant.core.services

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.util.Locale

/** Last failure metadata only: no prompts, keys, URLs or raw provider messages. */
class GeminiFailureMemory internal constructor(private val read: () -> String?, private val write: (String) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis) {
    constructor(context: Context) : this(
        { context.applicationContext.getSharedPreferences("jarvis_gemini_failure", Context.MODE_PRIVATE).getString("last", null) },
        { context.applicationContext.getSharedPreferences("jarvis_gemini_failure", Context.MODE_PRIVATE).edit().putString("last", it).commit() })

    fun record(code: Int, response: JSONObject?): Boolean = synchronized(lock) {
        if (code != 0 && code !in 400..599) return@synchronized false
        val error = response?.optJSONObject("error")
        val status = token(error?.optString("status").orEmpty())
        val details = error?.optJSONArray("details")
        val reasons = if (details == null) emptyList() else (0 until minOf(details.length(), 8))
            .mapNotNull { token(details.optJSONObject(it)?.optString("reason").orEmpty()).takeIf(String::isNotEmpty) }.distinct()
        runCatching { write(JSONObject().put("code", code).put("status", status).put("reasons", JSONArray(reasons)).put("created", now()).toString()) }.getOrDefault(false)
    }

    fun explain(): String = synchronized(lock) {
        val json = runCatching { JSONObject(read() ?: return@synchronized NO_RECORD) }.getOrNull() ?: return@synchronized NO_RECORD
        val code = json.optInt("code", -1)
        if ((code != 0 && code !in 400..599) || json.optLong("created", -1) < 0) return@synchronized NO_RECORD
        val status = token(json.optString("status"))
        val array = json.optJSONArray("reasons") ?: JSONArray()
        val reasons = (0 until minOf(array.length(), 8)).mapNotNull { token(array.optString(it)).takeIf(String::isNotEmpty) }
        val why = when {
            code == 0 -> "A connection or response-reading failure occurred without a usable HTTP error. I can't tell from this record whether the cause was your connection, a timeout or the provider."
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
        val diagnostic = if (code == 0) "connection/response failure" else (listOf("HTTP $code") + listOf(status).filter { it.isNotEmpty() } + reasons).distinct().joinToString(", ")
        "Last recorded Gemini failure: $diagnostic.\n$why\nThis is a local explanation of the last failure, not a live outage check. It doesn't establish whether live chat is active."
    }

    companion object {
        private val lock = Any()
        private const val NO_RECORD = "No Gemini failure details are recorded on this device yet. This update records future failures; I can't reconstruct an earlier error from the chat."
        private fun token(value: String) = value.takeIf { Regex("[A-Z_]{1,80}").matches(it) }.orEmpty()
        fun isQuestion(text: String): Boolean {
            val key = text.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!').replace(Regex("\\s+"), " ")
                .removePrefix("hey jarvis ").removePrefix("hey jarvis, ")
            return key in setOf("why did gemini reject the request", "why did gemini reject that request", "why did gemini reject my request",
                "why did gemini fail", "why did gemini fail the request", "explain the last gemini error", "what was the last gemini error", "show last gemini error",
                "how do i fix the gemini error", "how can i fix the gemini error", "how do i fix that gemini error", "how do i fix the last gemini error",
                "how do i fix the error", "how can i fix this error")
        }
    }
}
