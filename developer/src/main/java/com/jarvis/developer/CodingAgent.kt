package com.jarvis.developer

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Minimal real coding-agent loop: fetch source, ask model for a bounded edit, commit, observe CI.
 * Writes only Developer-owned source paths on the isolated feature branch.
 * API credentials stay in memory. Never silently merges, releases or installs.
 */
object CodingAgent {
    private const val REPO = "muddigger782012/J.A.R.V.I.S.-"
    private const val BRANCH = "feature/jarvis-developer"
    private const val ICON = "developer/src/main/res/drawable/developer_icon.xml"
    private const val BUILD = "release-jarvis-developer.yml"
    private fun api(method: String, url: String, token: String? = null, payload: JSONObject? = null): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 90000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "JarvisDeveloper-Agent")
        if (!token.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $token")
        if (payload != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
        }
        try {
            if (payload != null) c.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = c.responseCode
            val raw = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code: ${raw.take(250)}")
            return JSONObject(raw)
        } finally { c.disconnect() }
    }
    private fun read(token: String, path: String): Pair<String, String> {
        val j = api("GET", "https://api.github.com/repos/$REPO/contents/$path?ref=$BRANCH", token)
        val bytes = Base64.decode(j.getString("content").replace("\n", ""), Base64.DEFAULT)
        return j.getString("sha") to String(bytes, Charsets.UTF_8)
    }
    private fun generate(provider: String, key: String, model: String, task: String, original: String, context: String): String {
        val instructions = """You are a bounded Android code-editing agent. Return ONLY the complete valid Android vector drawable XML to replace developer_icon.xml. Preserve its vector root and xmlns. No markdown or commentary. Task: $task
Existing drawable:
$original
Relevant source context:
${context.take(12000)}
"""
        val raw: String = if (provider == "Gemini") {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/${model.ifBlank { "gemini-2.5-flash" }}:generateContent"
            val req = JSONObject().put("contents", JSONArray().put(JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", instructions)))))
            val c = URL(endpoint).openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 90000; c.doOutput = true
            c.setRequestProperty("x-goog-api-key", key); c.setRequestProperty("Content-Type", "application/json")
            try {
                c.outputStream.use { it.write(req.toString().toByteArray()) }
                val code = c.responseCode
                val response = (if (code in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) error("Gemini HTTP $code: ${response.take(250)}")
                val parts = JSONObject(response).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts")
                (0 until parts.length()).joinToString("\n") { parts.getJSONObject(it).optString("text") }
            } finally { c.disconnect() }
        } else {
            val req = JSONObject().put("model", model.ifBlank { "gpt-4.1-mini" })
                .put("input", instructions)
            val c = URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 90000; c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer $key"); c.setRequestProperty("Content-Type", "application/json")
            try {
                c.outputStream.use { it.write(req.toString().toByteArray()) }
                val code = c.responseCode
                val response = (if (code in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) error("OpenAI HTTP $code: ${response.take(250)}")
                val items = JSONObject(response).getJSONArray("output")
                buildString {
                    for (i in 0 until items.length()) {
                        val parts = items.getJSONObject(i).optJSONArray("content") ?: continue
                        for (j in 0 until parts.length()) append(parts.getJSONObject(j).optString("text"))
                    }
                }
            } finally { c.disconnect() }
        }
        val cleaned = raw.trim().removePrefix("```xml").removePrefix("```").removeSuffix("```").trim()
        require(cleaned.startsWith("<?xml") || cleaned.startsWith("<vector")) { "Model did not return vector XML" }
        require(cleaned.contains("<vector") && cleaned.contains("</vector>")) { "Invalid vector XML response" }
        require(!cleaned.contains("<!DOCTYPE", ignoreCase = true) && !cleaned.contains("<!ENTITY", ignoreCase = true)) { "Unsafe XML" }
        require(cleaned.length <= 32000) { "Generated XML too large" }
        val parser = android.util.Xml.newPullParser()
        parser.setInput(cleaned.reader())
        var event = parser.next()
        while (event != org.xmlpull.v1.XmlPullParser.START_TAG && event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) event = parser.next()
        require(event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "vector") { "Expected vector drawable XML" }
        return cleaned
    }
    fun run(provider: String, aiKey: String, model: String, githubToken: String, task: String, update: (String) -> Unit) {
        thread {
            try {
                require(aiKey.isNotBlank() && githubToken.isNotBlank() && task.isNotBlank()) { "Provide AI key, GitHub token and task." }
                require(task.contains("icon", true) || task.contains("reactor", true)) { "Initial coding scope: launcher icon only." }
                update("Reading Developer icon and original Arc Reactor source...")
                val (sha, existing) = read(githubToken, ICON)
                val (_, source) = read(githubToken, "app/src/main/java/com/assistant/core/ArcReactorView.kt")
                update("Asking $provider to produce a source edit...")
                val edited = generate(provider, aiKey, model, task, existing, source)
                require(edited != existing) { "No change produced" }
                update("Committing model-generated Developer icon source...")
                val body = JSONObject()
                    .put("message", "feat(developer): agent-generated launcher icon update")
                    .put("branch", BRANCH).put("sha", sha)
                    .put("content", Base64.encodeToString(edited.toByteArray(), Base64.NO_WRAP))
                val commit = api("PUT", "https://api.github.com/repos/$REPO/contents/$ICON", githubToken, body)
                    .getJSONObject("commit").getString("sha")
                update("Committed ${commit.take(8)}. Waiting for Developer CI...")
                val url = "https://api.github.com/repos/$REPO/actions/workflows/$BUILD/runs?branch=$BRANCH&per_page=10"
                var result = "Build pending. Commit: $commit"
                for (attempt in 0 until 24) {
                    Thread.sleep(10000)
                    val runs = api("GET", url, githubToken).optJSONArray("workflow_runs") ?: JSONArray()
                    val run = (0 until runs.length()).map { runs.getJSONObject(it) }
                        .firstOrNull { it.optString("head_sha") == commit }
                    if (run != null) {
                        val state = run.optString("status")
                        result = "Commit ${commit.take(8)}: $state / ${run.optString("conclusion")}\n${run.optString("html_url")}"
                        update(result)
                        if (state == "completed") {
                            val runId = run.getLong("id")
                            val jobs = api("GET", "https://api.github.com/repos/$REPO/actions/runs/$runId/jobs?per_page=100", githubToken).optJSONArray("jobs") ?: JSONArray()
                            val failures = mutableListOf<String>()
                            for (i in 0 until jobs.length()) {
                                val steps = jobs.getJSONObject(i).optJSONArray("steps") ?: JSONArray()
                                for (j in 0 until steps.length()) {
                                    val step = steps.getJSONObject(j)
                                    if (step.optString("conclusion") == "failure") failures.add(step.optString("name"))
                                }
                            }
                            if (failures.isNotEmpty()) result += "\nFailed steps: " + failures.joinToString("; ")
                            break
                        }
                    }
                }
                update(result + "\nReview the commit and build before approving any release.")
            } catch (e: Exception) { update("Coding task failed: ${e.message}") }
        }
    }
}
