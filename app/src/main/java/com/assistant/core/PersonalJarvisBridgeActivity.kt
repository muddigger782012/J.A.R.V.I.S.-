package com.assistant.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.assistant.core.services.PersonalJarvisBridgeProtocol
import com.assistant.core.services.PersonalJarvisBridgeService
import com.assistant.core.services.PersonalJarvisBridgeToken
import com.assistant.core.services.IntelligenceRouting
import com.assistant.core.services.RuntimeSettings
import com.assistant.core.services.DirectOnlineClient
import com.assistant.core.services.FallbackAiClient
import com.assistant.core.services.LocalLfm3Client
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * A single explicit opt-in screen. Neither voice wake listening nor Beta is modified.
 */
class PersonalJarvisBridgeActivity : MenuActivity("Personal AI Bridge") {
    private lateinit var status: TextView
    private lateinit var providerSummary: TextView
    private val url = "http://127.0.0.1:${PersonalJarvisBridgeProtocol.PORT}/v1"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (packageName != "com.assistant.core") { finish(); return }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = SettingsMenuViews.dp(this@PersonalJarvisBridgeActivity, 18)
            setPadding(padding, padding, padding, padding)
            setBackgroundResource(R.drawable.glass_panel_surface)
        }
        fun text(value: String, size: Float = 15f): TextView = TextView(this).apply {
            this.text = value
            textSize = size
            setPadding(0, SettingsMenuViews.dp(this@PersonalJarvisBridgeActivity, 7), 0,
                SettingsMenuViews.dp(this@PersonalJarvisBridgeActivity, 7))
            panel.addView(this)
        }
        fun button(label: String, action: () -> Unit) {
            panel.addView(Button(this).apply { this.text = label; setOnClickListener { action() } })
        }
        text("Connect other Android apps to Personal J.A.R.V.I.S.", 20f)
        text("This connection runs only on your phone, uses your existing Personal AI provider selection, and never grants the connecting app permission to run Personal Jarvis actions.")
        status = text("")
        providerSummary = text("")
        button("Start local AI bridge") {
            try {
                ContextCompat.startForegroundService(this,
                    Intent(this, PersonalJarvisBridgeService::class.java)
                        .setAction(PersonalJarvisBridgeService.ACTION_START))
                status.text = "Starting bridge; tap Check connection to verify."
            } catch (error: Exception) {
                status.text = "Android prevented the service from starting: ${error.javaClass.simpleName}. Open this page with the app foregrounded and try again."
            }
        }
        button("Stop bridge") {
            stopService(Intent(this, PersonalJarvisBridgeService::class.java))
            status.text = "Bridge stopping. Refresh status to confirm."
        }
        text("Base URL: $url")
        button("Copy base URL") { copy("Personal Jarvis base URL", url) }
        text("Model: ${PersonalJarvisBridgeProtocol.MODEL}")
        button("Copy model") { copy("Personal Jarvis model", PersonalJarvisBridgeProtocol.MODEL) }
        text("Access token: generated locally, stored in private no-backup storage. Do not publish or share it.")
        button("Copy access token") {
            try {
                copy("Personal Jarvis bridge token", PersonalJarvisBridgeToken.getOrCreate(this))
                status.text = "Token copied. Paste it into the other Jarvis app's API key field."
            } catch (_: Exception) { status.text = "Couldn't create a bridge token." }
        }
        button("Check connection") { checkConnection() }
        text("Check connection tests the local server only. Test AI response sends one prompt to your selected Personal provider and may incur cloud API usage.")
        button("Test AI response") { testAiResponse() }
        button("Regenerate access token") {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Replace the bridge access token?")
                .setMessage("The downloaded Jarvis will disconnect until you paste the new token into its API key field. No model settings or conversations are deleted.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Regenerate") { _, _ ->
                    try {
                        val newToken = PersonalJarvisBridgeToken.rotate(this)
                        copy("New Personal Jarvis bridge token", newToken)
                        status.text = "New token copied. Paste it into the downloaded Jarvis API key field; the previous token is now invalid."
                    } catch (_: Exception) { status.text = "Token rotation failed; inspect Personal app storage and retry." }
                }.show()
        }
        button("Refresh status") { refreshStatus() }
        text("In the downloaded Jarvis app: Settings → Model Provider Preset → Custom (OpenAI-compatible). Paste the URL, model, and token above, then Save changes.")
        text("AI routing: change the primary provider and fallback under Personal Jarvis → AI and weather. Gemini/gateway may send questions off this phone. Local LFM3 requires an actual loaded runtime. This bridge does not install Ollama or create an AI model.")
        setContentView(ScrollView(this).apply {
            val outer = SettingsMenuViews.dp(this@PersonalJarvisBridgeActivity, 12)
            setPadding(outer, outer, outer, outer)
            addView(panel)
        })
        FontPreferences.applyTo(panel, this)
        refreshStatus()
    }

    override fun onResume() { super.onResume(); if (::status.isInitialized) refreshStatus() }
    private fun refreshStatus() {
        status.text = if (PersonalJarvisBridgeService.listening) "Status: Listening on 127.0.0.1:8765"
            else "Status: Not listening. Start the bridge and check its connection."
        val settings = RuntimeSettings.read(this, "jarvis_cloud_ai")
        val provider = IntelligenceRouting.selected(settings.getString("primary_provider", "gemini"))
        val configured = when (provider) {
            IntelligenceRouting.Provider.GEMINI -> DirectOnlineClient(this).hasGeminiKey()
            IntelligenceRouting.Provider.GATEWAY -> FallbackAiClient(this).isConfigured()
            IntelligenceRouting.Provider.LOCAL_LFM3 -> LocalLfm3Client(this).isConfigured()
        }
        val fallback = settings.getBoolean("provider_fallback", true)
        providerSummary.text = "Selected Personal provider: ${provider.label} " +
            "(${if (configured) "configured" else "not configured"}). " +
            "Automatic fallback: ${if (fallback) "on" else "off"}. " +
            "Configuration alone does not prove the model is running."
    }

    private fun copy(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    }

    private fun testAiResponse() {
        status.text = "Sending one test question to the selected Personal AI provider..."
        Thread {
            val result = try {
                val request = JSONObject()
                    .put("model", PersonalJarvisBridgeProtocol.MODEL)
                    .put("stream", false)
                    .put("messages", JSONArray().put(JSONObject()
                        .put("role", "user")
                        .put("content", "Connection test: answer with READY. Do not perform any actions.")))
                    .toString().toByteArray(Charsets.UTF_8)
                val connection = URL("$url/chat/completions").openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 2000
                    connection.readTimeout = 45000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("Authorization",
                        "Bearer ${PersonalJarvisBridgeToken.getOrCreate(this)}")
                    connection.setFixedLengthStreamingMode(request.size)
                    connection.outputStream.use { it.write(request) }
                    when (connection.responseCode) {
                        200 -> {
                            val reply = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                                .getJSONArray("choices").getJSONObject(0)
                                .getJSONObject("message").optString("content")
                            if (reply.isNotBlank()) "AI test passed. Personal Jarvis answered through the bridge."
                            else "AI test failed: the provider returned an empty reply."
                        }
                        401 -> "Bridge refused its own token. Copy a new access token and retry."
                        503 -> "Bridge is reachable, but the selected AI provider failed. Check AI and weather settings, available cloud credentials, or the local LFM3 runtime."
                        else -> "AI test failed: HTTP ${connection.responseCode}. Recheck the bridge connection and Personal provider settings."
                    }
                } finally { connection.disconnect() }
            } catch (_: Exception) {
                "AI test could not complete. Check bridge status, provider availability and the phone's network."
            }
            runOnUiThread { if (!isFinishing && !isDestroyed) status.text = result }
        }.start()
    }

    private fun checkConnection() {
        status.text = "Checking the local endpoint..."
        Thread {
            val result = try {
                val connection = URL("$url/models").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 1500
                    connection.readTimeout = 3000
                    connection.setRequestProperty("Authorization", "Bearer ${PersonalJarvisBridgeToken.getOrCreate(this)}")
                    if (connection.responseCode == 200) "Connection passed: Personal AI bridge is reachable."
                    else "Connection failed: HTTP ${connection.responseCode}."
                } finally { connection.disconnect() }
            } catch (_: Exception) {
                "Connection failed. Start the bridge, then make sure no other service occupies port 8765."
            }
            runOnUiThread { if (!isFinishing && !isDestroyed) status.text = result }
        }.start()
    }
}
