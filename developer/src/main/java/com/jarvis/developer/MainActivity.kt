package com.jarvis.developer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Minimal independent OpenAI coding client.
 * API key is kept only in memory and is never embedded in the APK or persisted.
 * This is API communication, NOT a connection to a ChatGPT conversation or Codex session.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val health = getSharedPreferences("developer_health", MODE_PRIVATE)
        val currentCode = BuildConfig.VERSION_CODE
        val previousCode = health.getInt("last_started_version", -1)
        val previousConfirmed = health.getInt("confirmed_version", -1)
        val pendingReview = previousCode != currentCode || previousConfirmed != currentCode
        val now = System.currentTimeMillis()
        val lastLaunch = health.getLong("last_launch_ms", 0L)
        val lastLaunchVersion = health.getInt("last_launch_version", -1)
        val quickRestart = lastLaunchVersion == currentCode && lastLaunch > 0 &&
            now >= lastLaunch && now - lastLaunch < 120_000L
        val restarts = if (previousConfirmed != currentCode && quickRestart)
            (health.getInt("quick_restarts", 0) + 1).coerceAtMost(10) else 0
        val recoveryWarning = restarts >= 3 && previousConfirmed != currentCode
        health.edit().putInt("last_started_version", currentCode)
            .putInt("last_launch_version", currentCode)
            .putLong("last_launch_ms", now)
            .putInt("quick_restarts", restarts).apply()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(0xFF101820.toInt())
        }
        fun field(hint: String, multiline: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setHintTextColor(0xFF9DAFBE.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            if (multiline) {
                minLines = 3
                gravity = Gravity.TOP
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            root.addView(this)
        }
        val title = TextView(this).apply {
            text = "JARVIS DEVELOPER"
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
        }
        root.addView(title)
        val status = TextView(this).apply {
            text = "Standalone OpenAI coding client • Hybrid mode"
            setTextColor(0xFFB8C7D3.toInt())
        }
        root.addView(status)
        val key = field("Selected provider API key (not saved)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val provider = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, listOf("Gemini", "OpenAI"))
        }
        root.addView(provider)
        val model = field("Model ID (blank = provider default)")
        val prompt = field("Describe the code or change you need", true)
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            prompt.setText(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
        }
        val output = TextView(this).apply {
            setTextColor(0xFFE9F3FA.toInt())
            setTextIsSelectable(true)
            text = "Responses will appear here. GitHub writing and autonomous builds are not yet connected."
        }
        val send = Button(this).apply { text = "Ask coding assistant" }
        root.addView(send)
        root.addView(output)

        val plans = Button(this).apply { text = "Read approved development plans" }
        root.addView(plans)
        val builds = Button(this).apply { text = "Check Developer build status" }
        root.addView(builds)
        val updates = Button(this).apply { text = "Check for Developer updates" }
        root.addView(updates)
        val recoveryStatus = TextView(this).apply {
            setTextColor(0xFFE9F3FA.toInt())
            text = if (recoveryWarning) "WARNING: $restarts rapid launches on unconfirmed version $currentCode. Review recovery before coding or updating." else if (pendingReview) "Version $currentCode is awaiting your health confirmation." else
                "Version $currentCode was confirmed working on this device."
        }
        root.addView(recoveryStatus)
        val iconTest = Button(this).apply { text = "Generate icon code, commit and check build" }
        val confirmHealthy = Button(this).apply { text = "Confirm this version works" }
        root.addView(confirmHealthy)
        confirmHealthy.setOnClickListener {
            health.edit().putInt("confirmed_version", currentCode).putInt("quick_restarts", 0).apply()
            recoveryStatus.text = "Version $currentCode confirmed working locally."
            iconTest.isEnabled = true
            updates.isEnabled = true
        }
        val recovery = Button(this).apply { text = "Recovery / rebuild previous version" }
        root.addView(recovery)
        recovery.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("Jarvis Developer recovery")
                .setMessage("Installed version: $currentCode\\nLast locally confirmed version: ${if (previousConfirmed >= 0) previousConfirmed.toString() else "none"}\\n\\nTo recover from a bad update, open the GitHub workflow, choose Run workflow, and enter a verified working commit SHA in recovery_sha. The signed rebuild gets a new version code so Android can install it. This does not automatically roll back or bypass Android installation approval.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open recovery workflow") { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/workflows/release-jarvis-developer.yml")))
                }.show()
        }
        val githubToken = field("GitHub fine-grained token (not saved)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(iconTest)
        if (recoveryWarning) {
            iconTest.isEnabled = false
            updates.isEnabled = false
            status.text = "Recovery mode: AI edits and updates paused until this version is confirmed working."
        }
        iconTest.setOnClickListener {
            iconTest.isEnabled = false
            CodingAgent.run(provider.selectedItem.toString(), key.text.toString().trim(),
                model.text.toString().trim(), githubToken.text.toString().trim(),
                prompt.text.toString().trim()) { message ->
                runOnUiThread {
                    status.text = message.take(240)
                    output.text = message
                    if (message.startsWith("Coding task failed") ||
                        message.contains("Review the commit and build")) iconTest.isEnabled = true
                }
            }
        }
        updates.setOnClickListener { DeveloperUpdater.check(this) { message -> status.text = message } }
        plans.setOnClickListener {
            status.text = "Reading GitHub plans..."
            thread {
                try {
                    val approved = GithubPlans.listPlans().map { it to GithubPlans.plan(it) }
                        .filter { (_, body) -> Regex("(?im)^Status:\\s*approved\\s*$").containsMatchIn(body) }
                    runOnUiThread {
                        output.text = if (approved.isEmpty()) "No approved plans found." else
                            approved.joinToString("\n\n") { (name, body) -> "$name\n\n$body" }
                        status.text = "Plans loaded"
                    }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Plan lookup failed"; output.text = e.message }
                }
            }
        }
        builds.setOnClickListener {
            status.text = "Checking GitHub Actions..."
            thread {
                try {
                    val result = GithubPlans.latestBuild()
                    runOnUiThread { output.text = result; status.text = "Build status loaded" }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Build lookup failed"; output.text = e.message }
                }
            }
        }

        send.setOnClickListener {
            val apiKey = key.text.toString().trim()
            val task = prompt.text.toString().trim()
            if (apiKey.isEmpty() || task.isEmpty()) {
                status.text = "Enter an API key and task."
                return@setOnClickListener
            }
            send.isEnabled = false
            status.text = "Connecting to OpenAI..."
            thread {
                try {
                    val request = JSONObject()
                        .put("model", model.text.toString().trim().ifBlank { "gpt-4.1-mini" })
                        .put("instructions", "You are Jarvis Developer, a software engineering assistant. Provide actionable, correct code and clearly identify uncertainty. Never claim repository edits or builds unless tools actually performed them.")
                        .put("input", task)
                    val connection = (URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 15000
                        readTimeout = 90000
                        doOutput = true
                        setRequestProperty("Authorization", "Bearer $apiKey")
                        setRequestProperty("Content-Type", "application/json")
                    }
                    try {
                        connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
                        val code = connection.responseCode
                        val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)
                            ?.bufferedReader()?.use { it.readText() } ?: ""
                        val result = if (code in 200..299) {
                            val items = JSONObject(raw).optJSONArray("output") ?: JSONArray()
                            buildString {
                                for (i in 0 until items.length()) {
                                    val parts = items.optJSONObject(i)?.optJSONArray("content") ?: continue
                                    for (j in 0 until parts.length()) {
                                        val text = parts.optJSONObject(j)?.optString("text").orEmpty()
                                        if (text.isNotBlank()) append(text).append("\n")
                                    }
                                }
                            }.ifBlank { "No text response returned." }
                        } else "OpenAI HTTP $code: " + raw.take(700)
                        runOnUiThread {
                            output.text = result
                            status.text = if (code in 200..299) "Response received" else "Request failed"
                        }
                    } finally {
                        connection.disconnect()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        status.text = "Connection failed"
                        output.text = e.message ?: "Unknown network error"
                    }
                } finally {
                    runOnUiThread { send.isEnabled = true }
                }
            }
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }
}
