package com.assistant.core

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Base64
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
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
    // Stable per-install identifier required by the open-source ChatGPT OAuth flow.
    private val agentHostId: String by lazy {
        prefs.getString("ext_agent_host_id", null) ?: ("urn:uuid:" + UUID.randomUUID().toString()).also {
            prefs.edit().putString("ext_agent_host_id", it).commit()
        }
    }
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
        val connectButton = Button(this).apply { text = "ChatGPT connection · setup pending" }
        root.addView(connectButton)
        // Initialize once per app installation; re-use on later OAuth attempts.
        agentHostId
        connectButton.setOnClickListener { beginChatGptAuthorization() }
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
    /** OAuth first stage only. Never treat a browser callback as an authenticated session. */
    private fun beginChatGptAuthorization() {
        val bytes = ByteArray(32)
        val random = SecureRandom()
        random.nextBytes(bytes)
        val state = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        random.nextBytes(bytes)
        val nonce = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        random.nextBytes(bytes)
        val verifier = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        Thread {
            try {
                ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                    server.soTimeout = 180000
                    val redirect = "http://127.0.0.1:${server.localPort}/auth/callback"
                    val params = linkedMapOf(
                        "client_id" to "dynamic_agent_client",
                        "agent_name_hint" to "JARVIS",
                        "ext_agent_host_id" to agentHostId,
                        "response_type" to "code",
                        "redirect_uri" to redirect,
                        "scope" to "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
                        "resource" to "https://api.openai.com/v1",
                        "state" to state,
                        "nonce" to nonce,
                        "code_challenge_method" to "S256",
                        "code_challenge" to challenge
                    )
                    val url = Uri.parse("https://auth.openai.com/api/accounts/authorize").buildUpon().apply {
                        params.forEach { (key, value) -> appendQueryParameter(key, value) }
                    }.build()
                    runOnUiThread {
                        try { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                        catch (_: Exception) { Toast.makeText(this, "No browser available", Toast.LENGTH_LONG).show() }
                    }
                    server.accept().use { client ->
                        client.soTimeout = 10000
                        val reader = client.getInputStream().bufferedReader()
                        val request = reader.readLine() ?: ""
                        // Never log authorization codes or full callback URLs.
                        val path = request.split(" ").getOrNull(1) ?: ""
                        val callback = Uri.parse("http://127.0.0.1" + path)
                        val validPath = callback.path == "/auth/callback"
                        val validState = callback.getQueryParameter("state") == state
                        val codeReceived = !callback.getQueryParameter("code").isNullOrBlank()
                        val response = if (validPath && validState && codeReceived) {
                            "Authorization callback received. Token exchange and identity verification are not yet implemented; account is NOT connected."
                        } else {
                            "Authorization failed or callback validation rejected. Account is NOT connected."
                        }
                        val page = "<html><body><p>" + response + "</p></body></html>"
                        val payload = page.toByteArray(Charsets.UTF_8)
                        val header = "HTTP/1.1 200 OK" + "\\r\\n" +
                            "Content-Type: text/html; charset=utf-8" + "\\r\\n" +
                            "Content-Length: ${payload.size}" + "\\r\\n" +
                            "Connection: close" + "\\r\\n\\r\\n"
                        client.getOutputStream().write(header.toByteArray(Charsets.US_ASCII))
                        client.getOutputStream().write(payload)
                        runOnUiThread {
                            android.app.AlertDialog.Builder(this).setTitle("ChatGPT authorization")
                                .setMessage(response).setPositiveButton("OK", null).show()
                        }
                    }
                }
            } catch (_: SocketTimeoutException) {
                runOnUiThread { Toast.makeText(this, "ChatGPT authorization timed out", Toast.LENGTH_LONG).show() }
            } catch (_: Exception) {
                runOnUiThread { Toast.makeText(this, "ChatGPT authorization could not start", Toast.LENGTH_LONG).show() }
            }
        }.start()
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
