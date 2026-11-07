package com.assistant.core.services

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * The public API is intentionally text-only. Never turn an external request into
 * HybridAssistantService.handle(), a screen action, learning, or approval.
 */
object PersonalJarvisBridgeProtocol {
    const val MODEL = "personal-jarvis"
    const val PORT = 8765
    const val MAX_BODY_BYTES = 65536
    data class Prompt(val text: String, val history: List<Pair<String, String>>, val stream: Boolean)

    fun authorized(authorization: String?, expected: String): Boolean {
        if (expected.isBlank() || authorization == null || !authorization.startsWith("Bearer ")) return false
        return MessageDigest.isEqual(
            authorization.substringAfter("Bearer ").toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8)
        )
    }

    fun parse(body: String): Prompt {
        val request = JSONObject(body)
        require(request.optString("model") == MODEL) { "Unknown model. Choose personal-jarvis." }
        val messages = request.optJSONArray("messages") ?: throw IllegalArgumentException("messages must be an array")
        require(messages.length() in 1..32) { "messages must contain between 1 and 32 entries" }
        val system = StringBuilder()
        val history = mutableListOf<Pair<String, String>>()
        var pendingUser: String? = null
        var latestUser: String? = null
        var total = 0
        for (i in 0 until messages.length()) {
            val entry = messages.optJSONObject(i) ?: throw IllegalArgumentException("Invalid message")
            val role = entry.optString("role")
            val text = entry.opt("content") as? String ?: throw IllegalArgumentException("Only text messages are supported")
            total += text.length
            require(text.length <= 12000 && total <= 40000) { "Conversation exceeds size limit" }
            when (role) {
                "system", "developer" -> {
                    if (system.length < 6000) system.append(text.take(6000 - system.length)).append('\n')
                }
                "user" -> {
                    if (pendingUser != null) {
                        // An unanswered user turn cannot be history of an assistant answer.
                        pendingUser = null
                    }
                    pendingUser = text
                    latestUser = text
                }
                "assistant" -> {
                    if (pendingUser != null) history.add(pendingUser to text)
                    pendingUser = null
                }
                else -> throw IllegalArgumentException("Unsupported message role")
            }
        }
        require(!latestUser.isNullOrBlank() && pendingUser == latestUser) { "Last message must be a user text turn" }
        // The connecting app can supply instructions for its own reply format,
        // but nothing in the connected app grants Personal Jarvis device permissions.
        val prompt = buildString {
            append("You are the user's Personal J.A.R.V.I.S. conversation provider. ")
            append("Generate a text response in the format the connecting application requests: ")
            append("when asked for one JSON object, output valid JSON only, without fences or commentary. ")
            append("You do not execute device actions, install apps, or grant permissions. ")
            append("For action plans or JSON, describe an intended action only, not a completed action. ")
            append("The connecting application is responsible for separately confirming and performing actions.\n")
            if (system.isNotBlank()) append("Connected app's conversation instructions:\n").append(system).append('\n')
            append("User: ").append(latestUser)
        }
        return Prompt(prompt.take(20000), history.takeLast(8), request.optBoolean("stream", false))
    }

    fun models(): String = JSONObject()
        .put("object", "list")
        .put("data", JSONArray().put(JSONObject()
            .put("id", MODEL).put("object", "model").put("owned_by", "personal-jarvis")))
        .toString()

    fun completion(answer: String, created: Long = System.currentTimeMillis() / 1000): String =
        JSONObject().put("id", "chatcmpl-personal-jarvis").put("object", "chat.completion")
            .put("created", created).put("model", MODEL)
            .put("choices", JSONArray().put(JSONObject().put("index", 0)
                .put("message", JSONObject().put("role", "assistant").put("content", answer.take(12000)))
                .put("finish_reason", "stop"))).toString()

    /** A single final SSE delta is valid OpenAI-style streaming; token-by-token is not promised. */
    fun stream(answer: String, created: Long = System.currentTimeMillis() / 1000): String {
        val first = JSONObject().put("id", "chatcmpl-personal-jarvis")
            .put("object", "chat.completion.chunk").put("created", created).put("model", MODEL)
            .put("choices", JSONArray().put(JSONObject().put("index", 0)
                .put("delta", JSONObject().put("role", "assistant").put("content", answer.take(12000)))
                .put("finish_reason", JSONObject.NULL)))
        val done = JSONObject().put("id", "chatcmpl-personal-jarvis")
            .put("object", "chat.completion.chunk").put("created", created).put("model", MODEL)
            .put("choices", JSONArray().put(JSONObject().put("index", 0)
                .put("delta", JSONObject()).put("finish_reason", "stop")))
        return "data: $first\n\ndata: $done\n\ndata: [DONE]\n\n"
    }

    fun error(message: String, code: String): String = JSONObject()
        .put("error", JSONObject().put("message", message).put("type", "invalid_request_error").put("code", code))
        .toString()
}
