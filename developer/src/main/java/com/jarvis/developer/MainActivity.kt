package com.jarvis.developer

import android.app.Activity
import android.content.Intent
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
        val key = field("OpenAI API key (not saved)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val model = field("Model ID (default: gpt-4.1-mini)")
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
        val githubToken = field("GitHub fine-grained token (not saved)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val iconTest = Button(this).apply { text = "Run self-icon coding test" }
        root.addView(iconTest)
        iconTest.setOnClickListener {
            iconTest.isEnabled = false
            IconTask.run(githubToken.text.toString().trim(), prompt.text.toString()) { message ->
                runOnUiThread {
                    status.text = message
                    if (message.startsWith("Icon committed") || message.startsWith("Icon task failed")) iconTest.isEnabled = true
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
