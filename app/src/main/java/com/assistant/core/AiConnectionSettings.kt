package com.assistant.core

import android.app.Activity
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.switchmaterial.SwitchMaterial

/** One provider-settings destination shared by the hub and intelligence dashboard. */
object AiConnectionSettings {
    fun show(activity: Activity) {
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
        val geminiModel = field("Gemini model", "gemini_model").apply {
            if (text.isBlank()) setText("gemini-3.5-flash-lite")
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
        val dialog = ThemedDialogBuilder(activity)
            .setTitle("Weather and AI")
            .setView(scroll).setNegativeButton("Cancel", null)
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
                    gatewayPrefs.edit().putBoolean("weather_device_location", deviceLocation.isChecked).putString("gemini_model", geminiModel.text.toString().trim()).putString("endpoint", url)
                        .putString("gateway_token", token.text.toString().trim())
                        .putString("weather_latitude", lat).putString("weather_longitude", lon).apply()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
}
