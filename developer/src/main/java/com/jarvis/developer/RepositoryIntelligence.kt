package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/**
 * Phase 1 repository discovery. Read-only by construction: no GitHub write methods.
 * This produces a bounded inventory and evidence-based file suggestions before coding.
 */
object RepositoryIntelligence {
    private const val REPOSITORY = "muddigger782012/J.A.R.V.I.S.-"
    private const val BRANCH = "feature/jarvis-developer"
    private const val MAX_FILES = 2500
    private const val MAX_RESULTS = 20

    data class SourceFile(val path: String, val sha: String, val size: Long)
    data class FileMatch(val file: SourceFile, val score: Int, val reasons: List<String>)
    data class Inventory(val files: List<SourceFile>, val truncated: Boolean, val commitSha: String)

    private fun getJson(url: String, token: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "JarvisDeveloper-ReadOnly")
        if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
        try {
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) error("Repository read failed (HTTP $status)")
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    /** Lists source paths at one immutable commit; rejects truncated GitHub trees. */
    fun inventory(token: String): Inventory {
        val branch = URLEncoder.encode(BRANCH, "UTF-8")
        val ref = getJson("https://api.github.com/repos/$REPOSITORY/git/ref/heads/$branch", token)
        val sha = ref.getJSONObject("object").getString("sha")
        require(sha.matches(Regex("[a-fA-F0-9]{40}"))) { "Invalid repository revision" }
        val tree = getJson("https://api.github.com/repos/$REPOSITORY/git/trees/$sha?recursive=1", token)
        val truncated = tree.optBoolean("truncated", false)
        val nodes = tree.optJSONArray("tree") ?: JSONArray()
        val files = ArrayList<SourceFile>()
        for (index in 0 until nodes.length()) {
            val node = nodes.getJSONObject(index)
            if (node.optString("type") != "blob") continue
            val path = node.optString("path")
            if (!path.startsWith("developer/") || !path.matches(Regex("[A-Za-z0-9_./-]+"))) continue
            files.add(SourceFile(path, node.optString("sha"), node.optLong("size", 0)))
            if (files.size > MAX_FILES) error("Developer source inventory exceeds safety limit")
        }
        require(!truncated) { "GitHub returned a truncated tree; cannot provide a complete inventory" }
        return Inventory(files.sortedBy { it.path }, false, sha)
    }

    /** Heuristic candidate ranking only; it does not assert that a file is a dependency. */
    fun suggestFiles(task: String, inventory: Inventory): List<FileMatch> {
        val words = task.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }.distinct().take(25)
        if (words.isEmpty()) return emptyList()
        return inventory.files.mapNotNull { file ->
            val path = file.path.lowercase()
            val base = path.substringAfterLast('/')
            val reasons = words.filter { word -> base.contains(word) || path.split('/').any { it.contains(word) } }
            if (reasons.isEmpty()) null else FileMatch(file, reasons.sumOf { if (base.contains(it)) 3 else 1 }, reasons)
        }.sortedWith(compareByDescending<FileMatch> { it.score }.thenBy { it.file.path })
            .take(MAX_RESULTS)
    }

    /** Fetches one bounded Developer-owned source file at the inventory's pinned commit. */
    fun readSource(token: String, inventory: Inventory, path: String): String {
        val file = inventory.files.firstOrNull { it.path == path }
            ?: error("File not present in the pinned Developer inventory")
        require(file.size in 0..200_000) { "File exceeds read limit" }
        val encodedPath = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8") }
        val json = getJson(
            "https://api.github.com/repos/$REPOSITORY/contents/$encodedPath?ref=${inventory.commitSha}",
            token
        )
        require(json.optString("sha") == file.sha) { "File changed since inventory" }
        require(json.optString("encoding") == "base64") { "Unsupported GitHub file encoding" }
        val decoded = android.util.Base64.decode(json.getString("content"), android.util.Base64.DEFAULT)
        require(decoded.size <= 200_000) { "Source exceeds read limit" }
        return String(decoded, Charsets.UTF_8)
    }
}
