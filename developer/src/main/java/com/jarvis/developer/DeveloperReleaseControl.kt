package com.jarvis.developer

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Owner-controlled final handoff: verify the canonical source and its exact CI,
 * then dispatch an existing signed release workflow. Does NOT sign locally,
 * merge a PR, download an APK, or install anything.
 */
internal object DeveloperReleaseControl {
    private const val API = "https://api.github.com/repos/muddigger782012/J.A.R.V.I.S.-"
    private const val BRANCH = "feature/jarvis-developer"
    private const val RELEASE_WORKFLOW = "release-jarvis-developer.yml"
    private const val CI_WORKFLOW = "build-jarvis-developer.yml"
    private val shaPattern = Regex("[a-f0-9]{40}")
    private val tokenPattern = Regex("[A-Za-z0-9_\\-]{8,4096}")

    data class Candidate(val sha: String, val runId: Long)

    data class Reply(val status: Int, val json: JSONObject? = null)
    fun interface Transport {
        fun request(method: String, path: String, token: String, body: JSONObject?): Reply
    }

    internal class Client(private val transport: Transport = http()) {
        fun inspect(token: String): Candidate {
            require(tokenPattern.matches(token)) { "Connect GitHub in Settings to verify this release." }
            val ref = transport.request("GET", "/git/ref/heads/$BRANCH", token, null)
            require(ref.status == 200) { "Unable to verify canonical Developer source." }
            val sha = ref.json?.optJSONObject("object")?.optString("sha")
                ?.lowercase(Locale.ROOT).orEmpty()
            require(shaPattern.matches(sha)) { "Canonical source revision is unverified." }
            val path = "/actions/workflows/$CI_WORKFLOW/runs?branch=$BRANCH&head_sha=$sha&per_page=5"
            val runs = transport.request("GET", path, token, null)
            require(runs.status == 200) { "Unable to verify exact Developer CI." }
            val records = runs.json?.optJSONArray("workflow_runs")
                ?: error("Exact Developer CI evidence is unavailable.")
            val latest = (0 until records.length()).mapNotNull { records.optJSONObject(it) }
                .filter { it.optString("head_sha").lowercase(Locale.ROOT) == sha &&
                    it.optString("head_branch") == BRANCH }
                .maxByOrNull { it.optLong("run_number") }
                ?: error("No exact canonical Developer build was found.")
            require(latest.optString("status") == "completed" &&
                latest.optString("conclusion") == "success") {
                "Canonical Developer build is not fully green; release is blocked."
            }
            val runId = latest.optLong("id")
            require(runId > 0) { "CI run identity could not be verified." }
            return Candidate(sha, runId)
        }

        fun publish(token: String, expectedSha: String): Candidate {
            require(shaPattern.matches(expectedSha)) { "A complete approved source SHA is required." }
            // Mandatory fresh GET just before dispatch. No older success or moving
            // branch can be treated as a passing release candidate.
            val current = inspect(token)
            require(current.sha == expectedSha) {
                "Developer source changed after review. Check release readiness again."
            }
            val payload = JSONObject().put("ref", BRANCH).put("inputs", JSONObject()
                .put("expected_source_sha", expectedSha)
                .put("defer_recovery", "false")
                .put("recover_previous", "false"))
            val result = transport.request("POST",
                "/actions/workflows/$RELEASE_WORKFLOW/dispatches", token, payload)
            require(result.status == 204) {
                "GitHub did not accept the signed release workflow dispatch."
            }
            return current
        }
    }

    internal fun http(): Transport = Transport { method, path, token, body ->
        require(method == "GET" || method == "POST")
        require(tokenPattern.matches(token))
        require(path == "/git/ref/heads/$BRANCH" ||
            path.matches(Regex("/actions/workflows/build-jarvis-developer\\.yml/runs\\?branch=feature/jarvis-developer&head_sha=[a-f0-9]{40}&per_page=5")) ||
            (method == "POST" && path == "/actions/workflows/$RELEASE_WORKFLOW/dispatches"))
        require((method == "POST") == (body != null))
        val connection = URL(API + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 12_000
            connection.readTimeout = 18_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (method == "POST") {
                val bytes = requireNotNull(body).toString().toByteArray(Charsets.UTF_8)
                require(bytes.size < 2048)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status != 200 && status != 204) return@Transport Reply(status)
            if (status == 204) return@Transport Reply(status)
            val result = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    require(count > 0 && result.size() + count <= 128_000) {
                        "GitHub verification response exceeded the safe limit."
                    }
                    result.write(buffer, 0, count)
                }
            }
            Reply(200, JSONObject(result.toString("UTF-8")))
        } catch (_: java.io.IOException) {
            throw IllegalStateException("GitHub release verification could not connect.")
        } finally {
            connection.disconnect()
        }
    }
}

/** Exact two-stage release commands: no natural-language implication grants approval. */
internal object DeveloperReleaseChatCommand {
    private val publish = Regex("^publish developer update ([a-f0-9]{40})$", RegexOption.IGNORE_CASE)
    fun check(message: String): Boolean =
        message.trim().lowercase(Locale.ROOT).trimEnd('.', '!', '?') in
            setOf("check developer release", "check developer release readiness",
                "prepare developer release", "prepare developer update")
    fun publishSha(message: String): String? =
        publish.matchEntire(message.trim())?.groupValues?.get(1)?.lowercase(Locale.ROOT)
    fun publishAttempt(message: String): Boolean =
        message.trim().lowercase(Locale.ROOT).startsWith("publish developer update")
}
