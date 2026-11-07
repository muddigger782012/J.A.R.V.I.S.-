package com.jarvis.integration

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Shared Strata desktop-server protocol. Model output never executes a device action. */
object StrataApi {
    data class Config(val server: String, val token: String = "", val model: String = "strata")
    data class Message(val role: String, val content: String)
    class Failure(val status: Int?, message: String) : java.io.IOException(message)

    /** Developer's encrypted credential field holds URL#key, with #key optional. */
    fun fromConnectionField(value: String, model: String = ""): Config {
        val server = value.substringBefore('#').trim()
        val token = value.substringAfter('#', "").trim()
        return Config(server, token, model.ifBlank { "strata" }).also { endpoint(it.server) }
    }

    private fun privateAddress(host: String): Boolean {
        if (host.equals("localhost", true) || host == "::1" || host == "[::1]") return true
        val pieces = host.split('.')
        if (pieces.size != 4 || pieces.any { it.isEmpty() || (it.length > 1 && it[0] == '0') ||
                    it.length > 3 || !it.all(Char::isDigit) }) return false
        val octets = pieces.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val a = octets[0]
        val b = octets[1]
        return a == 10 || a == 127 || (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) || (a == 100 && b in 64..127)
    }

    /** HTTPS everywhere; HTTP only to literal LAN, Tailscale or loopback addresses. */
    fun endpoint(base: String): URL {
        val uri = URI(base.trim())
        val scheme = uri.scheme?.lowercase()
        val host = uri.host ?: throw IllegalArgumentException("Missing Strata server host")
        require(uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null)
        require(uri.port in -1..65535 && uri.port != 0)
        require(uri.path.orEmpty().trimEnd('/') in setOf("", "/v1"))
        require(scheme == "https" || (scheme == "http" && privateAddress(host))) {
            "Use HTTPS or HTTP with a private-network IP"
        }
        return URL(scheme, host, uri.port, "/v1/chat/completions")
    }

    fun supported(base: String): Boolean = runCatching { endpoint(base); true }.getOrDefault(false)

    fun parseText(root: JSONObject): String {
        val choices = root.getJSONArray("choices")
        require(choices.length() > 0) { "No Strata completion was returned" }
        val message = choices.getJSONObject(0).getJSONObject("message")
        require(message.optString("role", "assistant") == "assistant")
        require((message.optJSONArray("tool_calls")?.length() ?: 0) == 0) {
            "Model requested tools; use a reviewed tool executor"
        }
        val content = message.opt("content")
        val output = when (content) {
            is String -> content
            is JSONArray -> (0 until content.length()).mapNotNull { index ->
                val part = content.optJSONObject(index)
                if (part?.optString("type") == "text") part.optString("text") else null
            }.joinToString("")
            else -> ""
        }
        require(output.isNotBlank()) { "Strata returned no text" }
        return output
    }

    fun ask(config: Config, messages: List<Message>, maxTokens: Int = 8192,
            onConnection: ((HttpURLConnection) -> Closeable?)? = null): String {
        val destination = endpoint(config.server)
        require(config.model.matches(Regex("[A-Za-z0-9._:/-]{1,128}")))
        require(config.token.length <= 4096 && !config.token.contains('\n') && !config.token.contains('\r'))
        require(maxTokens in 1..131072)
        require(messages.isNotEmpty() && messages.size <= 100)
        val items = JSONArray()
        for (message in messages) {
            require(message.role in setOf("system", "user", "assistant"))
            require(message.content.isNotBlank() && message.content.length <= 256000)
            items.put(JSONObject().put("role", message.role).put("content", message.content))
        }
        val request = JSONObject().put("model", config.model).put("messages", items)
            .put("stream", false).put("max_tokens", maxTokens).toString().toByteArray(Charsets.UTF_8)
        require(request.size <= 1_048_576) { "Strata prompt exceeds transport budget" }
        val connection = destination.openConnection() as HttpURLConnection
        val cancel = onConnection?.invoke(connection)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 240000
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            if (config.token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer " + config.token)
            connection.setFixedLengthStreamingMode(request.size)
            connection.outputStream.use { it.write(request) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val explanation = when (status) {
                    401, 403 -> "Strata rejected the sharing key"
                    404 -> "Strata completion route or model unavailable"
                    429 -> "Strata server is busy"
                    in 500..599 -> "Strata engine/server failure"
                    else -> "Strata request rejected"
                }
                throw Failure(status, explanation + " (HTTP " + status + ")")
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, 2_097_152 - output.size() + 1))
                    if (count == -1) break
                    require(count > 0 && output.size() + count <= 2_097_152) { "Strata reply exceeds transport budget" }
                    output.write(buffer, 0, count)
                }
            }
            val decoded = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray())).toString()
            return parseText(JSONObject(decoded))
        } finally {
            cancel?.close()
            connection.disconnect()
        }
    }
}
