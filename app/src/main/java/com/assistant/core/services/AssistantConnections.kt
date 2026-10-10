package com.assistant.core.services

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

object AssistantConnectionProtocol {
    data class Request(val provider: String, val text: String)
    fun parse(text: String): Request? {
        val match = Regex("(?i)^(?:please\\s+)?(?:ask\\s+)?(home assistant|mycroft|openvoiceos)(?:\\s+to)?[,:]?\\s+(.+)$").matchEntire(text.trim()) ?: return null
        val provider = when (match.groupValues[1].lowercase(java.util.Locale.ROOT)) {
            "home assistant" -> "home_assistant"
            else -> "mycroft"
        }
        return Request(provider, match.groupValues[2].trim())
    }
    fun validEndpoint(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null
    }.getOrDefault(false)
    fun homeReply(json: JSONObject): String {
        val response = json.optJSONObject("response") ?: return "Home Assistant returned no usable response."
        val speech = response.optJSONObject("speech")
        speech?.optJSONObject("plain")?.optString("speech")?.takeIf { it.isNotBlank() }?.let { return it }
        return when (response.optString("response_type")) {
            "action_done" -> "Home Assistant reports that the action completed."
            "error" -> "Home Assistant couldn't handle that request. Check the exposed devices and command."
            else -> "Home Assistant returned no spoken answer."
        }
    }
}

class AssistantConnections(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_assistant_connections", Context.MODE_PRIVATE)
    private val homeToken = ApiKeyStore(context, "home_assistant")
    private val bridgeToken = ApiKeyStore(context, "assistant_bridge")
    fun ask(request: AssistantConnectionProtocol.Request): String {
        val home = request.provider == "home_assistant"
        val endpoint = prefs.getString(if (home) "home_url" else "bridge_url", "").orEmpty()
        val token = if (home) homeToken.read() else bridgeToken.read()
        val label = if (home) "Home Assistant" else "Mycroft"
        if (!AssistantConnectionProtocol.validEndpoint(endpoint) || token.isBlank())
            return "Set up $label in Voice Settings → Assistant connections first. You need your own server and credentials."
        val body = if (home) JSONObject().put("text", request.text).put("language", prefs.getString("language", "en"))
        else JSONObject().put("session_id", "android").put("provider", request.provider).put("learn_intent", false)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", request.text)))
        if (home) prefs.getString("home_agent", "").orEmpty().takeIf { it.isNotBlank() }?.let { body.put("agent_id", it) }
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(if (home) endpoint.trimEnd('/') + "/api/conversation/process" else endpoint).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.writer(Charsets.UTF_8).use { it.write(body.toString()) }
            val code = connection.responseCode
            if (code !in 200..299) return when (code) {
                401, 403 -> "$label rejected the credentials. Check Assistant connections settings."
                else -> "$label couldn't complete the request (HTTP $code). Check the server configuration."
            }
            val json = connection.inputStream.reader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            if (home) AssistantConnectionProtocol.homeReply(json) else json.optString("reply").trim().takeIf { it.isNotBlank() }
                ?: "$label returned no usable answer."
        } catch (_: Exception) { "$label connection failed. The server may have received your request; check the device before retrying." }
        finally { connection?.disconnect() }
    }
}
