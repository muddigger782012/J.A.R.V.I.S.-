package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Public repository read-only API. No GitHub token or extra permission required. */
object GithubPlans {
    const val REPO = "muddigger782012/J.A.R.V.I.S.-"
    const val BRANCH = "feature/jarvis-developer"
    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "JarvisDeveloper/0.1")
        try {
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("GitHub HTTP $code: ${text.take(180)}")
            return text
        } finally {
            connection.disconnect()
        }
    }

    fun listPlans(): List<String> {
        val url = "https://api.github.com/repos/$REPO/contents/docs/development-plans?ref=$BRANCH"
        val array = JSONArray(get(url))
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.takeIf { it.optString("type") == "file" }
                ?.optString("name")?.takeIf { it.endsWith(".md") }
        }
    }

    fun plan(name: String): String {
        require(Regex("[A-Za-z0-9_.-]+\\.md").matches(name)) { "Invalid plan name" }
        val url = "https://raw.githubusercontent.com/$REPO/$BRANCH/docs/development-plans/$name"
        return get(url)
    }

    fun latestBuild(): String {
        val url = "https://api.github.com/repos/$REPO/actions/workflows/build-jarvis-developer.yml/runs?branch=$BRANCH&per_page=1"
        val runs = JSONObject(get(url)).optJSONArray("workflow_runs") ?: JSONArray()
        val run = runs.optJSONObject(0) ?: return "No Developer build runs found."
        return "Run #${run.optInt("run_number")} — ${run.optString("status")} / ${run.optString("conclusion")}\n${run.optString("html_url")}"
    }
}
