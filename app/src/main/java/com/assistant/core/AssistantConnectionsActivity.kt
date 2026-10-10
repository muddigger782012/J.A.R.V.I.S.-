package com.assistant.core

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.assistant.core.services.ApiKeyStore
import com.assistant.core.services.AssistantConnectionProtocol

class AssistantConnectionsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "Assistant connections"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val prefs = getSharedPreferences("jarvis_assistant_connections", MODE_PRIVATE)
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 24, 32, 24) }
        setContentView(ScrollView(this).apply { addView(fields) })
        fun note(value: String) { fields.addView(TextView(this).apply { text = value; setTextColor(androidx.core.content.ContextCompat.getColor(this@AssistantConnectionsActivity, R.color.jarvis_on_dark_muted)); setPadding(0, 16, 0, 8) }) }
        fun field(label: String, value: String, secret: Boolean = false): EditText {
            note(label)
            return EditText(this).apply {
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or if (secret) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_NORMAL
                setText(value); fields.addView(this)
            }
        }
        note("Connect your own assistants. Only commands beginning with Home Assistant, Mycroft, or OpenVoiceOS go to these servers. Your existing local commands and Gemini continue to work.")
        note("Home Assistant Assist: run Home Assistant, expose the devices you want to control to Assist, and create a long-lived access token in your profile. Use an HTTPS address, including your Nabu Casa remote address if available.")
        val homeUrl = field("Home Assistant base URL (https://your-home-server)", prefs.getString("home_url", "").orEmpty())
        val homeKeys = ApiKeyStore(this, "home_assistant")
        val homeToken = field("Home Assistant long-lived access token", homeKeys.read(), true)
        val agent = field("Conversation agent ID (optional)", prefs.getString("home_agent", "").orEmpty())
        val language = field("Home Assistant language", prefs.getString("language", "en").orEmpty())
        note("Mycroft uses your optional dedicated gateway and a running compatible message bus. Deploy the adapter from this project's cloud-backend directory. J.A.R.V.I.S. does not need this server for its built-in phone commands, weather, or direct Gemini connection.")
        val bridgeUrl = field("Assistant gateway endpoint (https://your-server/chat)", prefs.getString("bridge_url", "").orEmpty())
        val bridgeKeys = ApiKeyStore(this, "assistant_bridge")
        val bridgeToken = field("Assistant gateway token", bridgeKeys.read(), true)
        note("Examples: Home Assistant turn on the kitchen lights. Mycroft what time is it?")
        note("Android App Actions declarations and launcher shortcuts are included. Google voice invocation needs App Actions preview or Play review; sideloading alone does not activate it.")
        fields.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = "Save connections"
            setOnClickListener {
                val home = homeUrl.text.toString().trim().trimEnd('/')
                val bridge = bridgeUrl.text.toString().trim()
                if ((home.isNotEmpty() && !AssistantConnectionProtocol.validEndpoint(home)) || (bridge.isNotEmpty() && !AssistantConnectionProtocol.validEndpoint(bridge))) {
                    Toast.makeText(this@AssistantConnectionsActivity, "Enter HTTPS addresses without credentials, query strings, or fragments.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if ((home.isBlank() != homeToken.text.toString().isBlank()) || (bridge.isBlank() != bridgeToken.text.toString().isBlank())) {
                    Toast.makeText(this@AssistantConnectionsActivity, "Enter both the address and token, or clear both.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                try { homeKeys.save(homeToken.text.toString().trim()); bridgeKeys.save(bridgeToken.text.toString().trim()) }
                catch (_: Exception) { Toast.makeText(this@AssistantConnectionsActivity, "Couldn't securely save tokens. Try again.", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                prefs.edit().putString("home_url", home).putString("home_agent", agent.text.toString().trim())
                    .putString("language", language.text.toString().trim().ifBlank { "en" }).putString("bridge_url", bridge).apply()
                Toast.makeText(this@AssistantConnectionsActivity, "Connections saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        })
    }
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
