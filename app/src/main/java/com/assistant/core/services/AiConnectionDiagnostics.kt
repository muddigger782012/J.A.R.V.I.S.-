package com.assistant.core.services

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/** Typed observations only. A probe is not a setting change, conversation or device action. */
object AiConnectionDiagnostics {
    enum class Problem { NOT_CONFIGURED, INVALID_REQUEST, DNS, TLS, TIMEOUT, CONNECTION, AUTHENTICATION,
        ACCESS, MODEL, QUOTA, SERVICE, FORMAT, LIMIT, UNEXPECTED_REPLY, UNKNOWN }
    enum class Purpose { CONNECTION_TEST, CONVERSATION }
    data class Catalogue(val models: List<String>, val complete: Boolean, val problem: Problem? = null, val http: Int? = null)
    data class Result(val success: Boolean, val modelListed: Boolean? = null, val catalogueProblem: Problem? = null,
        val problem: Problem? = null, val http: Int? = null, val elapsedMillis: Long = 0,
        val purpose: Purpose = Purpose.CONNECTION_TEST) {
        init { require(success == (problem == null)) }
    }
    fun problem(failure: Exception): Problem = when (failure) {
        is java.net.UnknownHostException -> Problem.DNS
        is javax.net.ssl.SSLException -> Problem.TLS
        is java.net.SocketTimeoutException -> Problem.TIMEOUT
        is ProviderReadLimit -> Problem.LIMIT
        is org.json.JSONException, is java.nio.charset.CharacterCodingException -> Problem.FORMAT
        is java.io.IOException -> Problem.CONNECTION
        is IllegalArgumentException -> Problem.INVALID_REQUEST
        else -> Problem.UNKNOWN
    }
    fun http(code: Int, response: JSONObject?): Problem {
        val reasons = response?.optJSONObject("error")?.optJSONArray("details")
        val invalidKey = reasons != null && (0 until minOf(reasons.length(), 8)).any {
            reasons.optJSONObject(it)?.optString("reason") == "API_KEY_INVALID"
        }
        return when {
            invalidKey || code == 401 -> Problem.AUTHENTICATION
            code == 403 -> Problem.ACCESS
            code == 404 -> Problem.MODEL
            code == 429 -> Problem.QUOTA
            code == 400 -> Problem.INVALID_REQUEST
            code in 500..599 -> Problem.SERVICE
            else -> Problem.UNKNOWN
        }
    }
    fun probe(configured: Boolean, model: String, catalogue: () -> Catalogue,
        generate: () -> Pair<Int, JSONObject?>, now: () -> Long = { System.nanoTime() / 1_000_000 }): Result {
        if (!configured) return Result(false, problem = Problem.NOT_CONFIGURED)
        if (!validModel(model)) return Result(false, problem = Problem.INVALID_REQUEST)
        val start = now()
        val listed = try { catalogue() } catch (failure: Exception) { Catalogue(emptyList(), false, problem(failure)) }
        val available = if (model in listed.models) true else if (listed.complete) false else null
        return try {
            // Catalog access and generation permissions can differ. Test generation independently.
            val (code, response) = generate()
            val accepted = code in 200..299 && IntelligenceConnectionCheck.accepted(response?.let(OnlineAnswers::geminiText))
            Result(accepted, available, listed.problem,
                if (accepted) null else if (code in 200..299) Problem.UNEXPECTED_REPLY else http(code, response),
                code.takeIf { it in 100..599 }, (now() - start).coerceAtLeast(0))
        } catch (failure: Exception) {
            Result(false, available, listed.problem, problem(failure), elapsedMillis = (now() - start).coerceAtLeast(0))
        }
    }
    fun validModel(model: String) = model.matches(Regex("[A-Za-z0-9._-]{1,128}")) &&
        !Regex("^(?:AIza|gh[pousr]_|github_pat_|sk-)").containsMatchIn(model)
    fun describe(problem: Problem?): String = when (problem) {
        null -> "no failure observed"
        Problem.NOT_CONFIGURED -> "Provider credentials are not configured for this test; review AI settings."
        Problem.INVALID_REQUEST -> "The request or model ID is invalid; review the selected model and request settings."
        Problem.DNS -> "DNS could not resolve the provider; check DNS and network filtering."
        Problem.TLS -> "HTTPS certificate negotiation failed; check device time and network filtering."
        Problem.TIMEOUT -> "The provider request timed out; retry explicitly when connectivity improves."
        Problem.CONNECTION -> "The connection or response stream was interrupted; no provider availability was verified."
        Problem.AUTHENTICATION -> "Authentication failed; check the saved API key without sharing it."
        Problem.ACCESS -> "The provider denied access; inspect project/key restrictions and regional access. The response does not establish which restriction caused it."
        Problem.MODEL -> "The selected model or endpoint was not found; use model discovery and preserve your manual choice until reviewed."
        Problem.QUOTA -> "The provider reported quota or rate limits; inspect quota and retry later."
        Problem.SERVICE -> "The provider reported a server-side failure; retry later or use the configured fallback."
        Problem.FORMAT -> "The provider response format could not be read."
        Problem.LIMIT -> "The response exceeded the bounded read or time limit."
        Problem.UNEXPECTED_REPLY -> "The model responded but did not return the connection-test marker; generation is not verified by this test."
        Problem.UNKNOWN -> "The observed failure could not be classified safely."
    }
    fun render(result: Result, provider: String = "Gemini"): String = buildString {
        append(if (result.purpose == Purpose.CONVERSATION) "$provider last conversation request: " else "$provider generation test: ")
        appendLine(if (result.success) "a usable model response was verified." else describe(result.problem))
        result.http?.let { appendLine("HTTP $it.") }
        appendLine(if (provider == "Gateway") "The gateway chooses its model on the server; no model catalog is exposed by this check."
            else "Selected-model catalog evidence: " + when (result.modelListed) { true -> "listed"; false -> "not listed"; null -> "unverified" })
        result.catalogueProblem?.let { appendLine("Catalog check: ${describe(it)}") }
        appendLine("Observed request/probe elapsed: ${result.elapsedMillis} ms.")
        append("This records a past request, not continuous connectivity or permission to perform actions.")
    }
    private fun identity(key: String, model: String) = MessageDigest.getInstance("SHA-256")
        .digest((key + "\u0000" + model).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    class Store(private val read: () -> String?, private val write: (String) -> Boolean,
        private val now: () -> Long = System::currentTimeMillis) {
        constructor(context: Context, provider: IntelligenceRouting.Provider = IntelligenceRouting.Provider.GEMINI) : this(
            { android.util.AtomicFile(java.io.File(context.filesDir, "ai-observation-${provider.key}.json")).openRead()
                .use { ProviderResponseText.read(it, 4096) } },
            { raw ->
                val file = android.util.AtomicFile(java.io.File(context.filesDir, "ai-observation-${provider.key}.json"))
                val output = file.startWrite()
                try { output.write(raw.toByteArray(Charsets.UTF_8)); file.finishWrite(output); true }
                catch (failure: Exception) { file.failWrite(output); throw failure }
            })
        fun record(key: String, model: String, result: Result): Boolean = runCatching {
            require(result.elapsedMillis in 0..300_000 && (result.http == null || result.http in 100..599))
            write(JSONObject().put("identity", identity(key, model)).put("created", now()).put("success", result.success)
                .put("listed", result.modelListed ?: JSONObject.NULL).put("catalogue", result.catalogueProblem?.name ?: JSONObject.NULL)
                .put("problem", result.problem?.name ?: JSONObject.NULL).put("http", result.http ?: JSONObject.NULL)
                .put("elapsed", result.elapsedMillis).put("purpose", result.purpose.name).toString())
        }.getOrDefault(false)
        fun current(key: String, model: String): Result? = runCatching {
            val raw = read() ?: return@runCatching null; require(raw.length <= 4096)
            val json = JSONObject(raw)
            require(json.getString("identity") == identity(key, model) && now() - json.getLong("created") in 0..900_000)
            val elapsed = json.getLong("elapsed"); require(elapsed in 0..300_000)
            val code = if (json.isNull("http")) null else json.getInt("http").also { require(it in 100..599) }
            Result(json.getBoolean("success"), if (json.isNull("listed")) null else json.getBoolean("listed"),
                if (json.isNull("catalogue")) null else Problem.valueOf(json.getString("catalogue")),
                if (json.isNull("problem")) null else Problem.valueOf(json.getString("problem")), code, elapsed,
                Purpose.valueOf(json.getString("purpose")))
        }.getOrNull()
    }
}

/** Reused by existing HTTPS readers; read limits apply while receiving bytes, not afterwards. */
internal class ProviderReadLimit : IllegalStateException()
internal object ProviderResponseText {
    fun read(input: java.io.InputStream, maximum: Int = 524_288): String {
        require(maximum in 1..2_097_152)
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val size = input.read(buffer, 0, minOf(buffer.size, maximum - output.size() + 1))
            if (size < 0) break
            if (size == 0 || output.size() + size > maximum) throw ProviderReadLimit()
            output.write(buffer, 0, size)
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
    }
}

/** Read timeouts alone do not bound a response that trickles bytes indefinitely. */
internal object ProviderDeadline {
    private val scheduler = java.util.concurrent.ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "jarvis-provider-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    fun <T> run(connection: java.net.HttpURLConnection, timeoutMillis: Long, block: () -> T): T {
        require(timeoutMillis in 1..120_000)
        val expired = java.util.concurrent.atomic.AtomicBoolean(false)
        val task = scheduler.schedule({ expired.set(true); connection.disconnect() }, timeoutMillis,
            java.util.concurrent.TimeUnit.MILLISECONDS)
        return try {
            val result = block()
            if (expired.get()) throw java.net.SocketTimeoutException()
            result
        } catch (failure: Exception) {
            if (expired.get()) throw java.net.SocketTimeoutException()
            throw failure
        } finally {
            task.cancel(false)
            connection.disconnect()
        }
    }
}
