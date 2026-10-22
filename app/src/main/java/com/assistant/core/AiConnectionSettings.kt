package com.assistant.core

import android.app.Activity
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.switchmaterial.SwitchMaterial

/** One provider-settings destination shared by the hub and intelligence dashboard. */
object AiConnectionSettings {
    fun show(activity: Activity) {
        var open = true
        val gatewayPrefs = activity.getSharedPreferences("jarvis_cloud_ai", android.content.Context.MODE_PRIVATE)
        val fields = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(SettingsMenuViews.dp(activity, 16), SettingsMenuViews.dp(activity, 8), SettingsMenuViews.dp(activity, 16), SettingsMenuViews.dp(activity, 8))
        }
        fun field(label: String, key: String, secret: Boolean = false): EditText {
            fields.addView(TextView(activity).apply { text = label })
            return EditText(activity).apply {
                setSingleLine(true)
                if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                setText(gatewayPrefs.getString(key, ""))
                fields.addView(this)
            }
        }
        fields.addView(TextView(activity).apply {
            text = "Weather uses National Weather Service directly. Gemini sends unresolved questions to Google using your own API key."
        })
        val keyStore = com.assistant.core.services.ApiKeyStore(activity)
        val geminiKey = field("Gemini API key (Google AI Studio)", "unused", true).apply { setText(keyStore.read()) }
        fields.addView(TextView(activity).apply { text = "Gemini AI model" })
        val geminiModel = EditText(activity).apply {
            setSingleLine(true)
            isFocusable = false
            isClickable = true
            setText(gatewayPrefs.getString("gemini_model", "gemini-3.5-flash-lite"))
            contentDescription = "Choose Gemini AI model"
            hint = "Tap to choose a model"
            fields.addView(this)
        }
        val modelOptions = listOf(
            "gemini-2.5-flash-lite" to "Fastest budget choice for simple requests",
            "gemini-2.5-flash" to "Balanced speed, reasoning and cost",
            "gemini-2.5-pro" to "Deeper reasoning and complex technical questions",
            "gemini-3-flash-preview" to "Newer Flash model; availability may vary",
            "gemini-3-pro-preview" to "Advanced reasoning; availability may vary",
            "Custom model ID…" to "Enter another model available to your API key"
        )
        geminiModel.setOnClickListener {
            val labels = modelOptions.map { (id, description) -> "$id\\n$description" }.toTypedArray()
            ThemedDialogBuilder(activity).setTitle("Choose Gemini model")
                .setSingleChoiceItems(labels, modelOptions.indexOfFirst { it.first == geminiModel.text.toString() }) { picker, index ->
                    val chosen = modelOptions[index].first
                    picker.dismiss()
                    if (chosen == "Custom model ID…") {
                        val custom = EditText(activity).apply {
                            setSingleLine(true)
                            hint = "e.g. gemini-2.5-flash"
                            setText(geminiModel.text)
                        }
                        ThemedDialogBuilder(activity).setTitle("Custom Gemini model ID")
                            .setView(custom)
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Use") { _, _ ->
                                val value = custom.text.toString().trim()
                                if (Regex("[a-zA-Z0-9._-]+").matches(value)) geminiModel.setText(value)
                            }.show()
                    } else geminiModel.setText(chosen)
                }.setNegativeButton("Cancel", null).show()
        }
        val discoverButton = com.google.android.material.button.MaterialButton(activity).apply {
            text = "Find Gemini models available to my API key"
        }
        val discoveryStatus = TextView(activity).apply {
            text = "Queries Google's model catalog using the entered API key."
            textSize = 12f
        }
        fields.addView(discoverButton)
        fields.addView(discoveryStatus)
        discoverButton.setOnClickListener {
            val apiKey = geminiKey.text.toString().trim()
            if (apiKey.isEmpty()) {
                discoveryStatus.text = "Enter your Gemini API key first."
                return@setOnClickListener
            }
            discoverButton.isEnabled = false
            discoveryStatus.text = "Checking available Gemini models..."
            kotlin.concurrent.thread(name = "JarvisGeminiModelDiscovery") {
                val models = try {
                    com.assistant.core.services.GeminiModelDiscovery.list(apiKey)
                } catch (_: Exception) { emptyList() }
                activity.runOnUiThread {
                    if (!open || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    discoverButton.isEnabled = true
                    if (models.isEmpty()) discoveryStatus.text =
                        "No models returned. Check your API key and network connection."
                    else {
                        discoveryStatus.text = "${models.size} text-generation models available."
                        ThemedDialogBuilder(activity).setTitle("Available Gemini models")
                            .setItems(models.toTypedArray()) { _, index ->
                                geminiModel.setText(models[index])
                                discoveryStatus.text = "Selected ${models[index]}. Tap Save to apply."
                            }.setNegativeButton("Cancel", null).show()
                    }
                }
            }
        }
        fields.addView(TextView(activity).apply {
            text = "Models vary by API key, region and availability. Selection changes both conversation and Google Search grounding; unsupported models will return a provider error."
            textSize = 12f
        })
        val personal = activity.packageName == "com.assistant.core"
        val providers = com.assistant.core.services.IntelligenceRouting.Provider.values()
        var primaryProvider = com.assistant.core.services.IntelligenceRouting.selected(gatewayPrefs.getString("primary_provider", "gemini"))
        val providerChoice = com.google.android.material.button.MaterialButton(activity).apply {
            text = "Conversation provider: ${primaryProvider.label}"
            setOnClickListener {
                ThemedDialogBuilder(activity).setTitle("Primary conversation provider")
                    .setSingleChoiceItems(providers.map { it.label }.toTypedArray(), providers.indexOf(primaryProvider)) { picker, index ->
                        primaryProvider = providers[index]; text = "Conversation provider: ${primaryProvider.label}"; picker.dismiss()
                    }.setNegativeButton("Cancel", null).show()
            }
        }
        val providerFallback = SwitchMaterial(activity).apply {
            text = "Try the other configured provider if the primary fails"
            isChecked = gatewayPrefs.getBoolean("provider_fallback", true)
        }
        if (personal) {
            fields.addView(providerChoice); fields.addView(providerFallback)
            fields.addView(TextView(activity).apply {
                text = "Supported local commands run first. Provider selection applies to complex conversation; attachments and Google Search grounding use Gemini. Your gateway uses its server-selected model. No subscription or cost-based routing limits apply to Personal Jarvis."
                textSize = 12f
            })
        }
        val endpoint = field("Optional gateway HTTPS URL ending in /chat", "endpoint")
        val token = field("Gateway token", "gateway_token", true)
        val deviceLocation = SwitchMaterial(activity).apply {
            text = "Use my precise phone location for weather"
            isChecked = gatewayPrefs.getBoolean("weather_device_location", false)
        }
        fields.addView(deviceLocation)
        val latitude = field("Weather latitude", "weather_latitude")
        val longitude = field("Weather longitude", "weather_longitude")
        fields.addView(com.google.android.material.button.MaterialButton(activity).apply {
            text = "Use Chesapeake, VA for weather"
            setOnClickListener { deviceLocation.isChecked = false; latitude.setText("36.7682"); longitude.setText("-76.2875") }
        })
        val scroll = android.widget.ScrollView(activity).apply { addView(fields) }
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        val page = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(SettingsMenuViews.navigation(activity, "AI settings", back = { dialog.dismiss() }, close = {
                dialog.dismiss()
                MenuActivity.closeMenus(activity)
            }))
            addView(scroll, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        }
        dialog = ThemedDialogBuilder(activity)
            .setTitle("Weather and AI")
            .setView(page).setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = endpoint.text.toString().trim()
                val uri = android.net.Uri.parse(url)
                val lat = latitude.text.toString().trim()
                val lon = longitude.text.toString().trim()
                if (url.isNotBlank() && (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null)) {
                    endpoint.error = "Enter a valid HTTPS gateway URL"
                } else if ((lat.isNotBlank() || lon.isNotBlank()) &&
                    (lat.toDoubleOrNull()?.let { it.isFinite() && it in -90.0..90.0 } != true ||
                     lon.toDoubleOrNull()?.let { it.isFinite() && it in -180.0..180.0 } != true)) {
                    latitude.error = "Enter valid latitude and longitude together"
                } else if (!Regex("[a-zA-Z0-9._-]+").matches(geminiModel.text.toString().trim())) {
                    geminiModel.error = "Enter a valid Gemini model name"
                } else {
                    try { keyStore.save(geminiKey.text.toString().trim()) } catch (_: Exception) {
                        geminiKey.error = "Couldn't save the API key securely. Try again."
                        return@setOnClickListener
                    }
                    if (personal) gatewayPrefs.edit().putString("primary_provider", primaryProvider.key)
                        .putBoolean("provider_fallback", providerFallback.isChecked).apply()
                    gatewayPrefs.edit().putBoolean("weather_device_location", deviceLocation.isChecked).putString("gemini_model", geminiModel.text.toString().trim()).putString("endpoint", url)
                        .putString("gateway_token", token.text.toString().trim())
                        .putString("weather_latitude", lat).putString("weather_longitude", lon).apply()
                    dialog.dismiss()
                }
            }
        }
        dialog.setOnDismissListener { open = false }
        dialog.show()
    }
}
