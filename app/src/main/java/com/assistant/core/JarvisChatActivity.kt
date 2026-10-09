package com.assistant.core

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Independent conversation UI; never executes device actions from AI output. */
class JarvisChatActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("jarvis_chat", MODE_PRIVATE) }
    private var session = ""
    private lateinit var transcript: TextView
    private lateinit var input: EditText
    private lateinit var endpoint: EditText
    private lateinit var gatewayToken: EditText
    private lateinit var messages: JSONArray

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 12, 16, 12); setBackgroundColor(Color.rgb(14, 21, 31)) }
        val title = TextView(this).apply { text = "JARVIS · AI Conversations"; textSize = 21f; setTextColor(Color.WHITE) }
        root.addView(title)
        val connectButton = Button(this).apply { text = "Connect with ChatGPT (setup)" }
        root.addView(connectButton)
        connectButton.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("ChatGPT account connection")
                .setMessage("JARVIS is preparing an open-source Sign in with ChatGPT connection. Authorization is not configured yet. Your ChatGPT password or session cookie must never be entered into JARVIS. Until OAuth registration and consent are implemented, the HTTPS gateway remains optional and local chat history works offline.")
                .setPositiveButton("OK", null).show()
        }
        endpoint = EditText(this).apply { hint = "HTTPS backend endpoint (no API keys)"; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY); setText(prefs.getString("endpoint", "")) }
        root.addView(endpoint)
        gatewayToken = EditText(this).apply { hint = "Gateway token (not saved)"; inputType = 129; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY); setText("") }
        root.addView(gatewayToken)
        val controls = LinearLayout(this)
        val newButton = Button(this).apply { text = "New chat" }
        val historyButton = Button(this).apply { text = "History" }
        controls.addView(newButton); controls.addView(historyButton); root.addView(controls)
        val scroll = ScrollView(this)
        transcript = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE); setPadding(8, 12, 8, 12) }
        scroll.addView(transcript); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val row = LinearLayout(this)
        input = EditText(this).apply { hint = "Ask Jarvis..."; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY) }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val send = Button(this).apply { text = "Send" }; row.addView(send); root.addView(row)
        setContentView(root)
        session = prefs.getString("active_session", "")!!.ifEmpty { UUID.randomUUID().toString() }
        loadSession()
        newButton.setOnClickListener { session = UUID.randomUUID().toString(); loadSession() }
        historyButton.setOnClickListener {
            val ids = prefs.getString("sessions", "")!!.split(",").filter { it.isNotBlank() }
            android.app.AlertDialog.Builder(this).setTitle("Conversations")
                .setItems(ids.toTypedArray()) { _, index -> session = ids[index]; loadSession() }.show()
        }
        send.setOnClickListener { sendMessage() }
    }
    private fun loadSession() {
        prefs.edit().putString("active_session", session).apply()
        messages = try { JSONArray(prefs.getString("chat_$session", "[]")) } catch (_: Exception) { JSONArray() }
        render()
    }
    private fun render() {
        transcript.text = (0 until messages.length()).joinToString("\n\n") { i ->
            val m = messages.getJSONObject(i)
            "${if (m.optString("role") == "user") "YOU" else "JARVIS"}: ${m.optString("content")}" }
    }
    private fun append(role: String, content: String) {
        messages.put(JSONObject().put("role", role).put("content", content))
        val ids = prefs.getString("sessions", "")!!.split(",").filter { it.isNotBlank() }.toMutableList()
        if (!ids.contains(session)) ids.add(0, session)
        prefs.edit().putString("sessions", ids.joinToString(",")).putString("chat_$session", messages.toString()).apply()
        render()
    }
    private fun sendMessage() {
        val prompt = input.text.toString().trim(); if (prompt.isEmpty()) return
        input.setText(""); append("user", prompt)
        val address = endpoint.text.toString().trim()
        if (!address.startsWith("https://")) {
            append("assistant", "Saved locally. Cloud AI is not connected yet. To enable ChatGPT replies, complete the account connection setup; JARVIS will not request your ChatGPT password.")
            return
        }
        prefs.edit().remove("gateway_token").putString("endpoint", address).apply()
        val history = messages.toString()
        val token = gatewayToken.text.toString()
        Thread {
            val reply = try {
                val conn = URL(address).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"; conn.connectTimeout = 15000; conn.readTimeout = 45000
                conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
                if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
                conn.outputStream.use { it.write(JSONObject().put("session_id", session).put("messages", JSONArray(history)).toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                conn.disconnect()
                if (code in 200..299) JSONObject(body).optString("reply", "Empty response") else "Backend error HTTP $code"
            } catch (e: Exception) { "Connection failed: ${e.javaClass.simpleName}" }
            runOnUiThread { append("assistant", reply) }
        }.start()
    }
}
