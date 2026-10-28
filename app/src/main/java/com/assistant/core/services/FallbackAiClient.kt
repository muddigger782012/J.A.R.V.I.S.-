package com.assistant.core.services

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class FallbackAiResult(val reply: String, val lessonJson: String?, val providerFailed: Boolean = false) {
    data class GatewayReply(val answer: FallbackAiResult, val problem: AiConnectionDiagnostics.Problem?)
    companion object {
        fun fromAssistantJson(raw: String): FallbackAiResult? = runCatching {
            val json = JSONObject(raw)
            val reply = (json.opt("reply") as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return null
            FallbackAiResult(reply, json.optJSONObject("lesson")?.toString())
        }.getOrNull()
        fun gatewayFailure(problem: AiConnectionDiagnostics.Problem, http: Int? = null): FallbackAiResult {
            val detail = when (problem) {
                AiConnectionDiagnostics.Problem.AUTHENTICATION -> "The gateway reported an authentication failure; review its saved token and server authentication configuration."
                AiConnectionDiagnostics.Problem.ACCESS -> "The gateway reported access denial; review token permissions and server access policy."
                AiConnectionDiagnostics.Problem.MODEL -> "The gateway reported a missing resource; review its HTTPS endpoint, server route and server-selected model."
                AiConnectionDiagnostics.Problem.INVALID_REQUEST -> "The gateway rejected the request; check server/API compatibility."
                else -> AiConnectionDiagnostics.describe(problem)
            }
            val status = http?.takeIf { it in 100..599 }?.let { " (HTTP $it)" }.orEmpty()
            return FallbackAiResult("Gateway$status: $detail Supported local commands remain available.", null, providerFailed = true)
        }
        /** Response metadata is untrusted; error bodies cannot become replies or learned actions. */
        fun gatewayResponse(code: Int, response: JSONObject?, learnIntent: Boolean): GatewayReply {
            if (code !in 200..299) {
                val problem = AiConnectionDiagnostics.http(code, response)
                return GatewayReply(gatewayFailure(problem, code), problem)
            }
            val reply = (response?.opt("reply") as? String)?.trim().orEmpty()
            if (reply.isBlank()) return GatewayReply(gatewayFailure(AiConnectionDiagnostics.Problem.FORMAT, code), AiConnectionDiagnostics.Problem.FORMAT)
            return GatewayReply(FallbackAiResult(reply, if (learnIntent) response?.optJSONObject("lesson")?.toString() else null), null)
        }
    }
}

/** Bounded interpretation-only fallback. No tool executes until the host reviews the result. */
object InterpretationFallback {
    fun ask(primary: () -> FallbackAiResult, alternative: (() -> FallbackAiResult?)? = null): FallbackAiResult {
        val first = runCatching(primary).getOrNull()
            ?: FallbackAiResult("The primary AI connection failed.", null, providerFailed = true)
        if (!first.providerFailed) return first
        val second = alternative?.let { runCatching(it).getOrNull() }?.takeIf { !it.providerFailed }
        return second ?: first.copy(lessonJson = null)
    }
}

/** Request construction is shared by normal chat and a location-free diagnostic check. */
object FallbackAiRequest {
    fun build(userText: String, learnIntent: Boolean, capabilityContext: String, latitude: Double?, longitude: Double?, includeLocation: Boolean): JSONObject {
        val request = JSONObject().put("session_id", "android").put("learn_intent", learnIntent)
            .put("capability_context", JSONObject(capabilityContext))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", userText)))
        if (includeLocation && latitude != null && longitude != null) {
            request.put("latitude", latitude).put("longitude", longitude)
        }
        return request
    }
}

class FallbackAiClient(private val context: Context) {
    private val prefs get() = RuntimeSettings.read(context, "jarvis_cloud_ai")

    fun isConfigured(): Boolean =
        validEndpoint(prefs.getString("endpoint", "").orEmpty()) &&
            prefs.getString("gateway_token", "").orEmpty().isNotBlank()

    private fun validEndpoint(endpoint: String): Boolean = runCatching {
        val url = java.net.URI(endpoint)
        url.scheme == "https" && !url.host.isNullOrBlank() && url.userInfo == null && url.fragment == null
    }.getOrDefault(false)
    private fun store() = AiConnectionDiagnostics.Store(context, IntelligenceRouting.Provider.GATEWAY)
    private fun identity(endpoint: String, token: String) = endpoint + "\u0000" + token
    fun connectionObservation(): AiConnectionDiagnostics.Result? = store().current(
        identity(prefs.getString("endpoint", "").orEmpty(), prefs.getString("gateway_token", "").orEmpty()), "server-selected")

    fun connectionTest(): AiConnectionDiagnostics.Result {
        val endpoint = prefs.getString("endpoint", "").orEmpty()
        val token = prefs.getString("gateway_token", "").orEmpty()
        val start = System.nanoTime()
        val result = if (!validEndpoint(endpoint) || token.isBlank()) {
            AiConnectionDiagnostics.Result(false, problem = AiConnectionDiagnostics.Problem.NOT_CONFIGURED)
        } else try {
            // No saved context, location, history, command learning or generated actions in a probe.
            val body = FallbackAiRequest.build(IntelligenceConnectionCheck.PROMPT, false, "{}", null, null, false)
            val (code, json) = request(endpoint, token, body.toString())
            val problem = if (code !in 200..299) AiConnectionDiagnostics.http(code, json)
                else if (!IntelligenceConnectionCheck.accepted(json?.optString("reply"))) AiConnectionDiagnostics.Problem.UNEXPECTED_REPLY else null
            AiConnectionDiagnostics.Result(problem == null, problem = problem, http = code)
        } catch (error: Exception) {
            AiConnectionDiagnostics.Result(false, problem = AiConnectionDiagnostics.problem(error))
        }
        val timed = result.copy(elapsedMillis = (System.nanoTime() - start).coerceAtLeast(0) / 1_000_000)
        store().record(identity(endpoint, token), "server-selected", timed)
        return timed
    }

    fun ask(userText: String, learnIntent: Boolean = true, capabilityContext: String = "{}", includeLocation: Boolean = true, conversation: List<Pair<String, String>> = emptyList()): FallbackAiResult? {
        val endpoint = prefs.getString("endpoint", "").orEmpty()
        val token = prefs.getString("gateway_token", "").orEmpty()
        if (!validEndpoint(endpoint) || token.isBlank()) return null

        val request = FallbackAiRequest.build(userText, learnIntent, capabilityContext,
            prefs.getString("weather_latitude", "").orEmpty().toDoubleOrNull(),
            prefs.getString("weather_longitude", "").orEmpty().toDoubleOrNull(), includeLocation)
        request.put("behavior_profile", AssistantPersonalityPreferences.behaviorProfile(context))
        val messages = JSONArray()
        conversation.takeLast(8).forEach { (user, assistant) ->
            messages.put(JSONObject().put("role", "user").put("content", user.take(12000)))
            messages.put(JSONObject().put("role", "assistant").put("content", assistant.take(12000)))
        }
        messages.put(JSONObject().put("role", "user").put("content", userText))
        request.put("messages", messages)
        val body = request.toString()

        val started = System.nanoTime()
        fun observe(problem: AiConnectionDiagnostics.Problem?, code: Int? = null) {
            store().record(identity(endpoint, token), "server-selected", AiConnectionDiagnostics.Result(problem == null,
                problem = problem, http = code, elapsedMillis = (System.nanoTime() - started).coerceAtLeast(0) / 1_000_000,
                purpose = AiConnectionDiagnostics.Purpose.CONVERSATION))
        }
        return try {
            val (code, json) = request(endpoint, token, body)
            val decoded = FallbackAiResult.gatewayResponse(code, json, learnIntent)
            observe(decoded.problem, code.takeIf { it in 100..599 })
            decoded.answer
        } catch (error: Exception) {
            val problem = AiConnectionDiagnostics.problem(error)
            observe(problem)
            FallbackAiResult.gatewayFailure(problem)
        }
    }

    private fun request(endpoint: String, token: String, body: String): Pair<Int, JSONObject?> {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return ProviderDeadline.run(connection, 37_000) {
            connection.requestMethod = "POST"
            connection.connectTimeout = 12_000
            connection.readTimeout = 25_000
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.outputStream.bufferedWriter().use { it.write(body) }
            val code = connection.responseCode
            val json = if (code in 200..299) connection.inputStream.use { JSONObject(ProviderResponseText.read(it)) }
                else runCatching { connection.errorStream?.use { JSONObject(ProviderResponseText.read(it)) } }.getOrNull()
            code to json
        }
    }
}
