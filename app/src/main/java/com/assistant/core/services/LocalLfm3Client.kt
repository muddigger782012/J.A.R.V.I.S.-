package com.assistant.core.services

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A model choice is not a downloaded model. llama-server loads exactly one GGUF at a time. */
object LocalLfm3Models {
    data class Variant(val key: String, val label: String, val alias: String, val repository: String, val ggufFile: String)
    val variants = listOf(
        Variant("atomic", "AtomicChat LFM3-1.7B Uncensored (Q4)", "jarvis-lfm3-atomic",
            "AtomicChat/LFM3-1.7B-Uncensored-GGUF", "LFM3-1.7B-Uncensored-AD-Q4_K_M.gguf"),
        Variant("extreme", "OpenIntelligenceNet LFM3-1.7B Uncensored Extreme (Q4)", "jarvis-lfm3-extreme",
            "OpenIntelligenceNet/LFM3-1.7B-Uncensored-Extreme-GGUF", "LFM3-1.7B-Uncensored-Q4_K_M-imatrix.gguf")
    )
    fun selected(key: String?): Variant = variants.firstOrNull { it.key == key } ?: variants.first()
    fun port(value: String?): Int? = value?.toIntOrNull()?.takeIf { it in 1024..65535 }
    fun modelAvailable(raw: String, alias: String): Boolean = runCatching {
        val data = JSONObject(raw).getJSONArray("data")
        (0 until data.length()).any { data.optJSONObject(it)?.optString("id") == alias }
    }.getOrDefault(false)

    /** No executable device tools are exposed to this conversational provider. */
    fun request(variant: Variant, input: String, history: List<Pair<String, String>>): String {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content",
            "You are J.A.R.V.I.S., a private, direct, natural conversational assistant. " +
            "Answer technical questions without needless moralizing. Do not invent facts. " +
            "You are a text-only inference engine and have no ability to perform phone actions. " +
            "Never claim to have performed an action; the host app handles any approved actions separately."))
        history.takeLast(6).forEach { (human, answer) ->
            messages.put(JSONObject().put("role", "user").put("content", human.take(4000)))
            messages.put(JSONObject().put("role", "assistant").put("content", answer.take(4000)))
        }
        messages.put(JSONObject().put("role", "user").put("content", input.take(12000)))
        return JSONObject().put("model", variant.alias).put("stream", false)
            .put("max_tokens", 384).put("temperature", 0.4)
            .put("messages", messages).toString()
    }

    fun response(raw: String): String? = runCatching {
        JSONObject(raw).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content").trim().takeIf { it.isNotBlank() }
    }.getOrNull()
}

/**
 * Personal-only loopback adapter for an on-device llama.cpp server.
 * Never connects to a LAN, cloud, configurable hostname or arbitrary endpoint.
 * The native inference runtime and GGUF weights are not bundled with this milestone.
 */
class LocalLfm3Client(private val context: Context) {
    private val prefs get() = RuntimeSettings.read(context, "jarvis_cloud_ai")
    private val personal get() = context.packageName == "com.assistant.core"
    private val variant get() = LocalLfm3Models.selected(prefs.getString("lfm3_variant", "atomic"))
    private val port get() = LocalLfm3Models.port(prefs.getString("lfm3_port", "8080"))
    fun isConfigured(): Boolean = personal && prefs.getBoolean("lfm3_enabled", false) && port != null
    fun selectedVariant(): LocalLfm3Models.Variant = variant
    fun embeddedRuntimeIncluded(): Boolean = EmbeddedLfm3Client(context).binaryIncluded()
    fun cancelEmbedded() = EmbeddedLfm3Client(context).cancel()

    private fun connect(path: String): HttpURLConnection = URL("http://127.0.0.1:" + requireNotNull(port) + path)
        .openConnection() as HttpURLConnection

    private fun loaded(): Boolean {
        val connection = connect("/v1/models")
        return ProviderDeadline.run(connection, 5000) {
            connection.requestMethod = "GET"
            connection.connectTimeout = 1500
            connection.readTimeout = 2500
            connection.instanceFollowRedirects = false
            connection.responseCode in 200..299 &&
                LocalLfm3Models.modelAvailable(connection.inputStream.use { ProviderResponseText.read(it, 65_536) }, variant.alias)
        }
    }

    private fun completion(prompt: String): String? {
        val connection = connect("/v1/chat/completions")
        return ProviderDeadline.run(connection, 32_000) {
            connection.requestMethod = "POST"
            connection.connectTimeout = 2000
            connection.readTimeout = 29_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.bufferedWriter().use { it.write(prompt) }
            if (connection.responseCode !in 200..299) null
            else LocalLfm3Models.response(connection.inputStream.use { ProviderResponseText.read(it) })
        }
    }

    /** Returns a text-only answer; no lesson or auto-executable action is accepted. */
    fun ask(input: String, history: List<Pair<String, String>> = emptyList(),
            current: () -> Boolean = { true }): FallbackAiResult {
        if (!current()) return FallbackAiResult("Earlier LFM3 response cancelled.", null, true)
        if (!isConfigured()) return FallbackAiResult("Local LFM3 is not enabled or its port is invalid. Review AI settings.", null, true)
        // A packaged ARM64 native runtime runs from verified app-private GGUF weights without Termux.
        EmbeddedLfm3Client(context).ask(input, history, current)?.let { return it }
        if (!current()) return FallbackAiResult("Earlier LFM3 response cancelled.", null, true)
        // The legacy loopback server remains a fallback for non-native development builds.
        return try {
            if (!current()) return FallbackAiResult("Earlier LFM3 response cancelled.", null, true)
            if (!loaded()) return FallbackAiResult(
                "Local LFM3 runtime is unavailable or has a different model loaded. Start llama-server on this phone with alias '" +
                    variant.alias + "' and the selected GGUF.", null, true)
            if (!current()) return FallbackAiResult("Earlier LFM3 response cancelled.", null, true)
            val reply = completion(LocalLfm3Models.request(variant, input, history))
            if (!current()) return FallbackAiResult("Earlier LFM3 response cancelled.", null, true)
            if (reply == null) FallbackAiResult("Local LFM3 returned no usable answer. Check its runtime logs.", null, true)
            else FallbackAiResult(reply, null)
        } catch (_: Exception) {
            FallbackAiResult("Local LFM3 did not respond. Check that the on-phone llama-server is running on port " +
                port + " and that Android has enough free memory.", null, true)
        }
    }

    fun connectionTest(): AiConnectionDiagnostics.Result {
        val start = System.nanoTime()
        if (!isConfigured()) return AiConnectionDiagnostics.Result(false, problem = AiConnectionDiagnostics.Problem.NOT_CONFIGURED)
        val embedded = EmbeddedLfm3Client(context)
        if (embedded.binaryIncluded() &&
            LocalLfm3ModelStore(context).status(variant).exists) {
            val reply = embedded.ask(IntelligenceConnectionCheck.PROMPT)
            val accepted = IntelligenceConnectionCheck.accepted(reply?.reply)
            return AiConnectionDiagnostics.Result(accepted, modelListed = true,
                problem = if (accepted) null else AiConnectionDiagnostics.Problem.UNEXPECTED_REPLY,
                elapsedMillis = (System.nanoTime() - start).coerceAtLeast(0) / 1_000_000)
        }
        return try {
            if (!loaded()) AiConnectionDiagnostics.Result(false, problem = AiConnectionDiagnostics.Problem.MODEL,
                elapsedMillis = (System.nanoTime() - start).coerceAtLeast(0) / 1_000_000)
            else {
                val prompt = LocalLfm3Models.request(variant, IntelligenceConnectionCheck.PROMPT, emptyList())
                val accepted = IntelligenceConnectionCheck.accepted(completion(prompt))
                AiConnectionDiagnostics.Result(accepted, modelListed = true,
                    problem = if (accepted) null else AiConnectionDiagnostics.Problem.UNEXPECTED_REPLY,
                    elapsedMillis = (System.nanoTime() - start).coerceAtLeast(0) / 1_000_000)
            }
        } catch (error: Exception) {
            AiConnectionDiagnostics.Result(false, problem = AiConnectionDiagnostics.problem(error),
                elapsedMillis = (System.nanoTime() - start).coerceAtLeast(0) / 1_000_000)
        }
    }
}
