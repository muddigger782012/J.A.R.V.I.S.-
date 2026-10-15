package com.jarvis.developer

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * First deliberately constrained coding action. It commits a real Android vector icon to
 * the Developer feature branch. The branch push triggers Developer CI. No production merge.
 * A fine-grained GitHub token with Contents: Read and write for this repo is required.
 */
object IconTask {
    private const val REPO = "muddigger782012/J.A.R.V.I.S.-"
    private const val BRANCH = "feature/jarvis-developer"
    private const val PATH = "developer/src/main/res/drawable/developer_icon.xml"
    private fun request(method: String, url: String, token: String, body: JSONObject? = null): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 25000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("User-Agent", "JarvisDeveloper")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("GitHub HTTP $code: ${text.take(240)}")
            return JSONObject(text)
        } finally { conn.disconnect() }
    }
    fun run(token: String, task: String, status: (String) -> Unit) {
        thread {
            try {
                require(token.isNotBlank()) { "Enter a fine-grained GitHub token with repository Contents write permission." }
                require(task.contains("icon", ignoreCase = true)) { "First milestone supports only an app icon task." }
                status("Reading icon source from GitHub...")
                val endpoint = "https://api.github.com/repos/$REPO/contents/$PATH"
                val current = request("GET", "$endpoint?ref=$BRANCH", token)
                val previousSha = current.getString("sha")
                // Deterministic, reviewable icon design. Future versions will use the coding agent.
                val icon = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#101820" android:pathData="M0,0h108v108h-108z"/>
    <path android:fillColor="#00E5FF" android:pathData="M54,7L101,54 54,101 7,54z"/>
    <path android:fillColor="#101820" android:pathData="M54,20L88,54 54,88 20,54z"/>
    <path android:fillColor="#00E5FF" android:pathData="M39,37h30v8h-30zM39,51h30v8h-30zM39,65h30v8h-30z"/>
    <path android:fillColor="#FFFFFF" android:pathData="M49,29h10v50h-10z"/>
</vector>
"""
                val encoded = Base64.encodeToString(icon.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                status("Committing icon change on Developer branch...")
                val body = JSONObject()
                    .put("message", "feat(developer): self-modify application icon via approved test task")
                    .put("content", encoded)
                    .put("sha", previousSha)
                    .put("branch", BRANCH)
                val updated = request("PUT", endpoint, token, body)
                val sha = updated.getJSONObject("commit").getString("sha")
                status("Icon committed: ${sha.take(8)}. GitHub Actions should start a Developer build. Check Build Status.")
            } catch (e: Exception) {
                status("Icon task failed: ${e.message ?: "Unknown error"}")
            }
        }
    }
}
