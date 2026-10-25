package com.assistant.core.services

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Same bounded model-discovery path used by settings and independent diagnostics. */
object GeminiModelDiscovery {
    const val DEFAULT_MODEL = "gemini-2.5-flash"
    internal fun interface Transport { fun get(url: String, apiKey: String, budgetMillis: Int): Pair<Int, JSONObject?> }
    private val http = Transport { url, key, budget ->
        val connection = URL(url).openConnection() as HttpURLConnection
        ProviderDeadline.run(connection, budget.toLong()) {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = minOf(15_000, budget); connection.readTimeout = minOf(20_000, budget)
            connection.setRequestProperty("x-goog-api-key", key)
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            fun read() = stream?.use { input -> JSONObject(ProviderResponseText.read(input, 256_000)) }
            // An unreadable optional error body must not erase a known quota/auth HTTP status.
            val response = if (code in 200..299) read() else runCatching { read() }.getOrNull()
            code to response
        }
    }
    fun list(apiKey: String): List<String> = inspect(apiKey).models
    fun inspect(apiKey: String): AiConnectionDiagnostics.Catalogue = inspect(apiKey, http)
    internal fun inspect(apiKey: String, transport: Transport,
        now: () -> Long = { System.nanoTime() / 1_000_000 }): AiConnectionDiagnostics.Catalogue {
        if (apiKey.isBlank()) return AiConnectionDiagnostics.Catalogue(emptyList(), false, AiConnectionDiagnostics.Problem.NOT_CONFIGURED)
        if (apiKey.length > 4096 || apiKey.any(Char::isISOControl))
            return AiConnectionDiagnostics.Catalogue(emptyList(), false, AiConnectionDiagnostics.Problem.INVALID_REQUEST)
        val available = linkedSetOf<String>()
        var page = ""
        val seen = HashSet<String>()
        val started = now()
        return try {
            repeat(5) {
                if (now() - started !in 0..75_000) throw ProviderReadLimit()
                val url = "https://generativelanguage.googleapis.com/v1beta/models" +
                    if (page.isBlank()) "" else "?pageToken=" + java.net.URLEncoder.encode(page, "UTF-8")
                val remaining = (75_000 - (now() - started)).coerceIn(1, 75_000).toInt()
                val (code, response) = transport.get(url, apiKey, remaining)
                if (code !in 200..299) return AiConnectionDiagnostics.Catalogue(available.toList(), false,
                    AiConnectionDiagnostics.http(code, response), code.takeIf { it in 100..599 })
                if (now() - started !in 0..75_000) throw ProviderReadLimit()
                val models = response?.optJSONArray("models") ?: throw org.json.JSONException("Missing model inventory")
                require(models.length() <= 500)
                for (index in 0 until models.length()) {
                    val model = models.getJSONObject(index)
                    val methods = model.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                    if ((0 until minOf(methods.length(), 32)).any { methods.optString(it) == "generateContent" }) {
                        val id = model.optString("name").removePrefix("models/")
                        if (id.matches(Regex("(?:gemini|gemma|learnlm)-[a-z0-9._-]{1,100}"))) available.add(id)
                    }
                }
                if (available.size > 500) throw ProviderReadLimit()
                page = response.optString("nextPageToken")
                if (page.isBlank()) return AiConnectionDiagnostics.Catalogue(available.toList(), true)
                require(page.length <= 1024 && seen.add(page))
            }
            AiConnectionDiagnostics.Catalogue(available.toList(), false, AiConnectionDiagnostics.Problem.LIMIT)
        } catch (failure: Exception) {
            AiConnectionDiagnostics.Catalogue(available.toList(), false, AiConnectionDiagnostics.problem(failure))
        }
    }
}
