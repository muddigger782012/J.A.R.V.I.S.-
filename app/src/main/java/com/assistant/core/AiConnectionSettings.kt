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
            setText(gatewayPrefs.getString("gemini_model", com.assistant.core.services.GeminiModelDiscovery.DEFAULT_MODEL))
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
                val inventory = com.assistant.core.services.GeminiModelDiscovery.inspect(apiKey)
                val models = inventory.models
                activity.runOnUiThread {
                    if (!open || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    discoverButton.isEnabled = true
                    if (models.isEmpty()) discoveryStatus.text = if (inventory.complete) "The catalog returned no supported text-generation models. Your selected model was preserved."
                        else com.assistant.core.services.AiConnectionDiagnostics.describe(inventory.problem)
                    else {
                        discoveryStatus.text = "${models.size} text-generation models listed." +
                            if (inventory.complete) " Catalog complete; generation has not been tested."
                            else " Catalog incomplete: " + com.assistant.core.services.AiConnectionDiagnostics.describe(inventory.problem)
                        ThemedDialogBuilder(activity).setTitle("Available Gemini models")
                            .setItems(models.toTypedArray()) { _, index ->
                                geminiModel.setText(models[index])
                                discoveryStatus.text = "Selected ${models[index]}. Tap Save to apply."
                            }.setNegativeButton("Cancel", null).show()
                    }
                }
            }
        }
        val testStatus = TextView(activity).apply {
            text = "A connection test sends only a short test prompt using the entered key and model. It does not save settings, use chat or location, or perform actions."
            textSize = 12f
        }
        val testButton = com.google.android.material.button.MaterialButton(activity).apply {
            text = "Test entered Gemini connection"
            setOnClickListener {
                val enteredKey = geminiKey.text.toString().trim()
                val enteredModel = geminiModel.text.toString().trim()
                isEnabled = false
                testStatus.text = "Testing catalog access and generation for the entered settings…"
                val appContext = activity.applicationContext
                kotlin.concurrent.thread(name = "JarvisGeminiConnectionCheck") {
                    val result = com.assistant.core.services.DirectOnlineClient(appContext).connectionTest(enteredKey, enteredModel)
                    activity.runOnUiThread {
                        if (!open || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        isEnabled = true
                        testStatus.text = com.assistant.core.services.AiConnectionDiagnostics.render(result) +
                            "\nTested the values entered when you pressed Test. Tap Save separately to apply edits."
                    }
                }
            }
        }
        fields.addView(testButton)
        fields.addView(testStatus)
        fields.addView(TextView(activity).apply {
            text = "Models vary by API key, region and availability. Selection changes both conversation and Google Search grounding; unsupported models will return a provider error."
            textSize = 12f
        })
        val personal = activity.packageName == "com.assistant.core"
        var downloadCancelledForExit: (() -> Unit)? = null
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
        var localVariant = com.assistant.core.services.LocalLfm3Models.selected(gatewayPrefs.getString("lfm3_variant", "atomic"))
        val localEnabled = SwitchMaterial(activity).apply {
            text = "Enable on-phone LFM3 runtime (Personal only)"
            isChecked = gatewayPrefs.getBoolean("lfm3_enabled", false)
        }
        val localModelChoice = com.google.android.material.button.MaterialButton(activity).apply {
            text = "Local GGUF: " + localVariant.label
            setOnClickListener {
                val variants = com.assistant.core.services.LocalLfm3Models.variants
                ThemedDialogBuilder(activity).setTitle("Choose uncensored LFM3 variant")
                    .setSingleChoiceItems(variants.map { it.label }.toTypedArray(), variants.indexOf(localVariant)) { picker, index ->
                        localVariant = variants[index]
                        text = "Local GGUF: " + localVariant.label
                        picker.dismiss()
                    }.setNegativeButton("Cancel", null).show()
            }
        }
        val localPort = EditText(activity).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(gatewayPrefs.getString("lfm3_port", "8080"))
            hint = "8080"
        }
        if (personal) {
            fields.addView(providerChoice); fields.addView(providerFallback)
            fields.addView(localEnabled)
            fields.addView(localModelChoice)
            fields.addView(TextView(activity).apply {
                text = "The selected GGUF must be loaded by llama-server running ON THIS PHONE, with model alias '" +
                    localVariant.alias + "' (see docs/PERSONAL_LFM3.md). Choosing a variant does not download or start it. " +
                    "LFM3 can answer freely, but phone actions still require host approval."
                textSize = 12f
            })
            fields.addView(TextView(activity).apply { text = "On-phone llama-server port (127.0.0.1 only)" })
            fields.addView(localPort)
            val modelStore = com.assistant.core.services.LocalLfm3ModelStore(activity)
            val downloadCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
            downloadCancelledForExit = { downloadCancelled.set(true) }
            val modelStatus = TextView(activity).apply {
                textSize = 12f
                setTextIsSelectable(true)
            }
            fun refreshModelStatus() {
                val snapshot = modelStore.status(localVariant)
                modelStatus.text = if (snapshot.exists)
                    "GGUF saved in app-private storage: ${snapshot.bytes / 1_000_000} MB. SHA-256 verified at download; use Verify for a fresh integrity check. In-app native model loading is the next milestone."
                else if (snapshot.partialBytes > 0)
                    "Partial GGUF: ${snapshot.partialBytes / 1_000_000} MB. Tap Download to resume."
                else "This model is not downloaded. Download is around 1.1 GB and requires enough free internal storage."
            }
            val downloadButton = com.google.android.material.button.MaterialButton(activity).apply {
                text = "Download selected uncensored GGUF"
            }
            val cancelDownloadButton = com.google.android.material.button.MaterialButton(activity).apply {
                text = "Pause model download"
                isEnabled = false
            }
            val verifyButton = com.google.android.material.button.MaterialButton(activity).apply {
                text = "Verify saved GGUF"
            }
            val deleteButton = com.google.android.material.button.MaterialButton(activity).apply {
                text = "Delete downloaded GGUF"
            }
            var modelOperation = false
            fun operationControls(busy: Boolean) {
                modelOperation = busy
                downloadButton.isEnabled = !busy
                cancelDownloadButton.isEnabled = busy
                verifyButton.isEnabled = !busy
                deleteButton.isEnabled = !busy
                localModelChoice.isEnabled = !busy
            }
            downloadButton.setOnClickListener {
                if (modelOperation) return@setOnClickListener
                val chosen = localVariant
                downloadCancelled.set(false)
                operationControls(true)
                modelStatus.text = "Preparing secure GGUF download for ${chosen.label}… Keep this settings page open; closing pauses the transfer."
                kotlin.concurrent.thread(name = "jarvis-lfm3-model-download") {
                    val result = runCatching {
                        modelStore.download(chosen, cancelled = { downloadCancelled.get() }) { pct ->
                            activity.runOnUiThread {
                                if (open && !activity.isFinishing) modelStatus.text = "Downloading / verifying ${chosen.label}: $pct%"
                            }
                        }
                    }
                    activity.runOnUiThread {
                        if (!open || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        operationControls(false)
                        modelStatus.text = result.fold(
                            onSuccess = { "Downloaded and verified ${it.bytes / 1_000_000} MB. GGUF is ready for native loader integration." },
                            onFailure = { "GGUF transfer: ${it.message ?: "failed"}. Any valid partial transfer remains resumable." }
                        )
                    }
                }
            }
            cancelDownloadButton.setOnClickListener {
                downloadCancelled.set(true)
                modelStatus.text = "Pausing after the current network read; reopen and resume when ready."
            }
            verifyButton.setOnClickListener {
                if (modelOperation) return@setOnClickListener
                val chosen = localVariant
                operationControls(true)
                modelStatus.text = "Checking GGUF SHA-256 against saved download receipt…"
                kotlin.concurrent.thread(name = "jarvis-lfm3-model-verify") {
                    val verified = runCatching { modelStore.verify(chosen) }.getOrDefault(false)
                    activity.runOnUiThread {
                        if (!open || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        operationControls(false)
                        modelStatus.text = if (verified) "Verified: saved GGUF matches its SHA-256 receipt."
                            else "Verification failed or no downloaded GGUF is present. Download again before using it."
                    }
                }
            }
            deleteButton.setOnClickListener {
                if (modelOperation) return@setOnClickListener
                val chosen = localVariant
                ThemedDialogBuilder(activity).setTitle("Delete local GGUF?")
                    .setMessage("Delete the downloaded ${chosen.label} model from private storage? This does not affect J.A.R.V.I.S. cloud AI or saved chats.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        modelStatus.text = if (modelStore.delete(chosen)) "Model deleted." else "Could not delete model."
                        refreshModelStatus()
                    }.show()
            }
            fields.addView(modelStatus)
            fields.addView(downloadButton)
            fields.addView(cancelDownloadButton)
            fields.addView(verifyButton)
            fields.addView(deleteButton)
            refreshModelStatus()
            fields.addView(TextView(activity).apply {
                text = "Privacy: turn OFF provider fallback above for strictly offline chat. If fallback is on and local LFM3 fails, the question may be sent to your configured Gemini or gateway service."
                textSize = 12f
            })
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
                } else if (personal && localEnabled.isChecked &&
                    com.assistant.core.services.LocalLfm3Models.port(localPort.text.toString().trim()) == null) {
                    localPort.error = "Enter a port from 1024 to 65535"
                } else if (!Regex("[a-zA-Z0-9._-]+").matches(geminiModel.text.toString().trim())) {
                    geminiModel.error = "Enter a valid Gemini model name"
                } else {
                    try { keyStore.save(geminiKey.text.toString().trim()) } catch (_: Exception) {
                        geminiKey.error = "Couldn't save the API key securely. Try again."
                        return@setOnClickListener
                    }
                    if (personal) gatewayPrefs.edit().putString("primary_provider", primaryProvider.key)
                        .putBoolean("provider_fallback", providerFallback.isChecked)
                        .putString("lfm3_variant", localVariant.key)
                        .putString("lfm3_port", localPort.text.toString().trim())
                        .putBoolean("lfm3_enabled", localEnabled.isChecked).apply()
                    gatewayPrefs.edit().putBoolean("weather_device_location", deviceLocation.isChecked).putString("gemini_model", geminiModel.text.toString().trim()).putString("endpoint", url)
                        .putString("gateway_token", token.text.toString().trim())
                        .putString("weather_latitude", lat).putString("weather_longitude", lon).apply()
                    dialog.dismiss()
                }
            }
        }
        dialog.setOnDismissListener {
            open = false
            // Model transfers are explicitly page-scoped and retain resumable partial bytes.
            if (personal) downloadCancelledForExit?.invoke()
        }
        dialog.show()
    }
}
