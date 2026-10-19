package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Timer
import java.util.TimerTask

/** Text-only provider access. No repository, file-write, tool, build or publisher capability exists here. */
object CodingAssistantProvider {
    internal const val MAX_REQUEST_BYTES = 64_000
    internal const val MAX_RESPONSE_BYTES = 256_000
    internal const val MAX_SESSION_MILLIS = 90_000L
    internal const val FAILURE = "Coding assistant request failed; check provider access, model and bounded request/output format."
    internal class ProviderFailure(message: String) : IllegalStateException(message)
    private fun httpMessage(code: Int): String = when (code) {
        400 -> "Provider rejected request (HTTP 400). Check model and settings."
        401, 403 -> "Provider denied access (HTTP $code). Check key and permissions."
        404 -> "Model not found (HTTP 404). Clear model field to use default."
        429 -> "Provider quota or rate limit reached (HTTP 429)."
        in 500..599 -> "AI provider temporarily unavailable (HTTP $code)."
        else -> "AI provider returned HTTP $code."
    }
    private const val OPENAI_URL = "https://api.openai.com/v1/responses"
    private const val GEMINI_PREFIX = "https://generativelanguage.googleapis.com/v1beta/models/"
    private val modelName = Regex("[a-z0-9][a-z0-9._-]{0,79}")
    private val geminiName = Regex("gemini-[a-z0-9][a-z0-9.-]{0,72}")
    private val keyPattern = Regex("[A-Za-z0-9._-]{8,1024}")

    internal data class PostRequest(val url: String, val headers: Map<String, String>, val body: ByteArray)
    internal fun interface PostTransport { fun post(request: PostRequest): PostResponse }
    internal class PostResponse(
        val status: Int,
        val contentLength: Long,
        val finalUrl: String,
        val body: InputStream,
        private val disconnect: () -> Unit = {}
    ) : Closeable {
        override fun close() { try { body.close() } finally { disconnect() } }
    }

    private val http = PostTransport { request ->
        requireProviderUrl(request.url)
        require(request.body.size <= MAX_REQUEST_BYTES)
        val connection = URL(request.url).openConnection() as HttpURLConnection
        val deadline = Timer("JarvisDeveloper-AssistantTimeout", true)
        deadline.schedule(object : TimerTask() {
            override fun run() { try { connection.disconnect() } catch (_: Exception) { /* never log provider errors */ } }
        }, MAX_SESSION_MILLIS)
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.useCaches = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.setFixedLengthStreamingMode(request.body.size)
            connection.outputStream.use { it.write(request.body) }
            val status = connection.responseCode
            // Error bodies can reflect credentials; never open or render them.
            val body = if (status in 200..299) connection.inputStream else ByteArrayInputStream(byteArrayOf())
            PostResponse(status, connection.contentLengthLong, connection.url.toString(), body) {
                deadline.cancel()
                connection.disconnect()
            }
        } catch (e: java.net.SocketTimeoutException) {
            deadline.cancel()
            connection.disconnect()
            throw ProviderFailure("AI connection timed out. Check network access and retry.")
        } catch (e: java.net.UnknownHostException) {
            deadline.cancel()
            connection.disconnect()
            throw ProviderFailure("AI provider hostname could not be resolved. Check internet or DNS.")
        } catch (e: java.net.ConnectException) {
            deadline.cancel()
            connection.disconnect()
            throw ProviderFailure("Could not connect to the AI provider. Check network access.")
        } catch (e: ProviderFailure) {
            deadline.cancel()
            connection.disconnect()
            throw e
        } catch (_: Exception) {
            deadline.cancel()
            connection.disconnect()
            throw IllegalStateException(FAILURE)
        }
    }

    fun availableGeminiModels(key: String): List<String> {
        require(keyPattern.matches(key))
        val available = linkedSetOf<String>()
        var nextPage = ""
        repeat(5) {
        val pageSuffix = if (nextPage.isBlank()) "" else "?pageToken=" + java.net.URLEncoder.encode(nextPage, "UTF-8")
        val connection = URL("https://generativelanguage.googleapis.com/v1beta/models" + pageSuffix).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.setRequestProperty("x-goog-api-key", key)
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code !in 200..299) throw ProviderFailure(httpMessage(code))
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = stream.read(buffer)
                    if (count == -1) break
                    require(count > 0 && bytes.size() + count <= MAX_RESPONSE_BYTES)
                    bytes.write(buffer, 0, count)
                }
            }
            val response = JSONObject(strictUtf8(bytes.toByteArray()))
            val models = response.optJSONArray("models") ?: JSONArray()
            val pageModels = (0 until models.length()).mapNotNull { index ->
                val item = models.getJSONObject(index)
                val methods = item.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                val name = item.optString("name").removePrefix("models/")
                if (geminiName.matches(name) &&
                    (0 until methods.length()).any { methods.optString(it) == "generateContent" }) name else null
            }
            available.addAll(pageModels)
            nextPage = response.optString("nextPageToken")
            if (nextPage.isBlank() || nextPage.length > 1024) return available.toList()
        } catch (e: ProviderFailure) {
            throw e
        } catch (_: Exception) {
            throw IllegalStateException("Unable to discover Gemini models.")
        } finally {
            connection.disconnect()
        }
        }
        return available.toList()
    }

    fun ask(provider: String, key: String, model: String, task: String): String = Client(http).ask(provider, key, model, task)

    /** Small bounded request for checking credentials/model access without a full coding response. */
    fun testConnection(provider: String, key: String, model: String): String =
        Client(http).ask(provider, key, model, "Reply with the single word connected.", 64)

    /** One text request, no retries. Injectable dependencies are instance-local and internal to tests. */
    internal class Client(
        private val transport: PostTransport,
        private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }
    ) {
        fun ask(provider: String, key: String, model: String, task: String, maxOutputTokens: Int = 8192): String {
            try {
                val started = nowMillis()
                val outbound = outbound(provider, key, model, task, maxOutputTokens)
                checkBudget(started)
                val responseText = transport.post(outbound).use { response ->
                    requireProviderUrl(response.finalUrl)
                    require(response.finalUrl == outbound.url)
                    if (response.status !in 200..299) throw ProviderFailure(httpMessage(response.status))
                    require(response.contentLength in -1..MAX_RESPONSE_BYTES.toLong())
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var count = 0
                    while (true) {
                        checkBudget(started)
                        val read = response.body.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES - count + 1))
                        checkBudget(started)
                        if (read == -1) break
                        require(read > 0)
                        count += read
                        require(count <= MAX_RESPONSE_BYTES)
                        bytes.write(buffer, 0, read)
                    }
                    strictUtf8(bytes.toByteArray())
                }
                val envelope = strictObject(responseText)
                // Check decoded values as well as field names, including unicode-escaped key echoes.
                rejectCredential(envelope, key)
                val text = when (provider) {
                    "OpenAI" -> openAiText(envelope)
                    "Gemini" -> geminiText(envelope)
                    else -> error("Unsupported provider")
                }
                require(text.isNotBlank() && !text.contains(key))
                checkBudget(started)
                return text
            } catch (e: ProviderFailure) {
                throw e
            } catch (e: java.net.SocketTimeoutException) {
                throw ProviderFailure("AI connection timed out. Check network access and retry.")
            } catch (e: java.net.UnknownHostException) {
                throw ProviderFailure("AI provider hostname could not be resolved. Check internet or DNS.")
            } catch (e: java.net.ConnectException) {
                throw ProviderFailure("Could not connect to the AI provider. Check network access.")
            } catch (_: Exception) {
                // Never expose exception details: they may contain credentials or provider bodies.
                throw IllegalStateException(FAILURE)
            }
        }

        private fun checkBudget(started: Long) {
            if (nowMillis() - started !in 0 until MAX_SESSION_MILLIS)
                throw ProviderFailure("AI request exceeded the 90-second session limit. Try a smaller task or faster model.")
        }
    }

    private fun outbound(provider: String, key: String, model: String, task: String, maxOutputTokens: Int): PostRequest {
        require(maxOutputTokens in 1..8192)
        require(keyPattern.matches(key))
        require(task.isNotBlank() && task.length <= MAX_REQUEST_BYTES && !task.contains(key))
        val instruction = "You are Jarvis Developer, a software engineering assistant. " +
            "Provide actionable, correct code and clearly identify uncertainty. " +
            "You have no repository, file-edit, shell, build, publishing or installation tools. " +
            "Never claim to have performed edits, builds, tests or other operations. " +
            "Return text only; do not request tools or echo credentials."
        val selectedModel = model.ifBlank { if (provider == "Gemini") "gemini-2.5-flash" else "gpt-4.1-mini" }
        val url: String
        val headers: Map<String, String>
        val body: JSONObject
        when (provider) {
            "OpenAI" -> {
                require(modelName.matches(selectedModel))
                url = OPENAI_URL
                headers = mapOf("Authorization" to "Bearer $key")
                body = JSONObject().put("model", selectedModel).put("store", false).put("max_output_tokens", maxOutputTokens)
                    .put("instructions", instruction).put("input", task)
            }
            "Gemini" -> {
                require(geminiName.matches(selectedModel))
                url = "$GEMINI_PREFIX${selectedModel}:generateContent"
                headers = mapOf("x-goog-api-key" to key)
                body = JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instruction))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", task)))))
                    .put("generationConfig", JSONObject().put("candidateCount", 1).put("maxOutputTokens", maxOutputTokens))
            }
            else -> error("Unsupported provider")
        }
        requireProviderUrl(url)
        val serialized = body.toString()
        require(!url.contains(key) && !serialized.contains(key))
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(serialized))
        require(encoded.remaining() <= MAX_REQUEST_BYTES)
        val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
        return PostRequest(url, headers, bytes)
    }

    private fun openAiText(root: JSONObject): String {
        require(root.get("status") == "completed")
        require(!root.has("error") || root.isNull("error"))
        require(!root.has("incomplete_details") || root.isNull("incomplete_details"))
        require(!root.has("output_text") && !root.has("tool_calls"))
        val output = root.getJSONArray("output")
        require(output.length() == 1)
        val message = output.getJSONObject(0)
        requireOnlyKeys(message, setOf("id", "type", "role", "status", "content"))
        require(message.get("type") == "message" && message.get("role") == "assistant" && message.get("status") == "completed")
        val content = message.getJSONArray("content")
        require(content.length() > 0)
        return (0 until content.length()).joinToString("\n") { index ->
            val text = content.getJSONObject(index)
            requireOnlyKeys(text, setOf("type", "text", "annotations", "logprobs"))
            require(text.get("type") == "output_text" && text.get("text") is String)
            if (text.has("annotations")) require(text.getJSONArray("annotations").length() == 0)
            if (text.has("logprobs") && !text.isNull("logprobs")) require(text.getJSONArray("logprobs").length() == 0)
            text.get("text") as String
        }
    }

    private fun geminiText(root: JSONObject): String {
        require(!root.has("error"))
        if (root.has("promptFeedback")) require(!root.getJSONObject("promptFeedback").has("blockReason"))
        val candidates = root.getJSONArray("candidates")
        require(candidates.length() == 1)
        val candidate = candidates.getJSONObject(0)
        require(candidate.get("finishReason") == "STOP")
        val content = candidate.getJSONObject("content")
        requireOnlyKeys(content, setOf("role", "parts"))
        require(content.get("role") == "model")
        val parts = content.getJSONArray("parts")
        require(parts.length() > 0)
        // Gemini can include non-text thinking metadata alongside the final text.
        // Only expose ordinary text parts; never surface thoughts or tool calls.
        return (0 until parts.length()).mapNotNull { index ->
            val part = parts.getJSONObject(index)
            require(!part.has("functionCall") && !part.has("executableCode") && !part.has("codeExecutionResult"))
            if (part.optBoolean("thought", false)) null
            else {
                require(part.has("text") && part.get("text") is String)
                part.getString("text")
            }
        }.joinToString("")
    }

    private fun rejectCredential(value: Any, key: String) {
        when (value) {
            is String -> require(!value.contains(key)) { "Provider credential appeared in output" }
            is JSONObject -> value.keys().forEach { name ->
                require(!name.contains(key)) { "Provider credential appeared in output" }
                rejectCredential(value.get(name), key)
            }
            is JSONArray -> for (index in 0 until value.length()) rejectCredential(value.get(index), key)
        }
    }

    private fun requireOnlyKeys(value: JSONObject, allowed: Set<String>) {
        value.keys().forEach { require(it in allowed) { "Unexpected provider output field" } }
    }

    private fun requireProviderUrl(value: String) {
        val uri = URI(value)
        require(uri.scheme == "https" && uri.port == -1 && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(value == OPENAI_URL || (value.startsWith(GEMINI_PREFIX) &&
            value.endsWith(":generateContent") && geminiName.matches(value.removePrefix(GEMINI_PREFIX).removeSuffix(":generateContent"))))
    }

    private fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private fun strictObject(text: String): JSONObject {
        // Android's JSONTokener is permissive. Validate standard JSON before handing it any input.
        JsonGrammar(text).validate()
        return JSONObject(text)
    }

    private class JsonGrammar(private val text: String) {
        private var cursor = 0
        private var nodes = 0
        fun validate() {
            whitespace()
            require(cursor < text.length && text[cursor] == '{')
            value(0)
            whitespace()
            require(cursor == text.length)
        }
        private fun value(depth: Int) {
            require(depth <= 32 && ++nodes <= 20_000)
            whitespace()
            require(cursor < text.length)
            when (text[cursor]) {
                '{' -> {
                    cursor++; whitespace()
                    val keys = HashSet<String>()
                    if (take('}')) return
                    do {
                        whitespace(); require(keys.add(string())); whitespace(); require(take(':'))
                        value(depth + 1); whitespace()
                        if (take('}')) return
                    } while (take(','))
                    error("Invalid JSON object")
                }
                '[' -> {
                    cursor++; whitespace()
                    if (take(']')) return
                    do { value(depth + 1); whitespace(); if (take(']')) return } while (take(','))
                    error("Invalid JSON array")
                }
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> {
                    val start = cursor
                    while (cursor < text.length && text[cursor] in "-+0123456789.eE") cursor++
                    require(text.substring(start, cursor).matches(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")))
                }
            }
        }
        private fun string(): String {
            require(take('"'))
            val result = StringBuilder()
            while (cursor < text.length) {
                val character = text[cursor++]
                if (character == '"') {
                    var index = 0
                    while (index < result.length) {
                        val current = result[index++]
                        if (Character.isHighSurrogate(current)) {
                            require(index < result.length && Character.isLowSurrogate(result[index++]))
                        } else require(!Character.isLowSurrogate(current))
                    }
                    return result.toString()
                }
                require(character.code >= 32)
                if (character != '\\') result.append(character) else {
                    require(cursor < text.length)
                    when (val escape = text[cursor++]) {
                        '"', '\\', '/' -> result.append(escape)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000c')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            require(cursor + 4 <= text.length)
                            val digits = text.substring(cursor, cursor + 4)
                            require(digits.matches(Regex("[0-9a-fA-F]{4}")))
                            result.append(digits.toInt(16).toChar()); cursor += 4
                        }
                        else -> error("Invalid JSON escape")
                    }
                }
            }
            error("Unterminated JSON string")
        }
        private fun literal(expected: String) { require(text.startsWith(expected, cursor)); cursor += expected.length }
        private fun take(character: Char): Boolean = if (cursor < text.length && text[cursor] == character) { cursor++; true } else false
        private fun whitespace() { while (cursor < text.length && text[cursor] in " \t\n\r") cursor++ }
    }
}
