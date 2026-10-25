package com.assistant.core.services

import android.content.Context
import java.util.Locale

/** Installed evidence and runtime availability, independent of feature requests or generated AI claims. */
object LiveCapabilityRegistry {
    enum class Topic { VERSION, FEATURES, SCREEN, VOICE, DIAGNOSTICS, PROVIDERS }
    data class Snapshot(val version: DeviceReport.App?, val personal: Boolean,
        val accessibility: DeviceReport.Accessibility?, val microphone: Boolean,
        val contacts: Boolean?, val calls: Boolean?, val notifications: Boolean?,
        val geminiConfigured: Boolean, val gatewayConfigured: Boolean, val selectedModel: String,
        val provider: IntelligenceRouting.Provider, val fallback: Boolean,
        val localVoice: VoiceRuntimeObservation.Reading?, val foregroundVoice: VoiceRuntimeObservation.Reading?,
        val buildNotes: List<String> = emptyList(),
        val geminiObservation: AiConnectionDiagnostics.Result? = null,
        val gatewayObservation: AiConnectionDiagnostics.Result? = null)

    fun topic(input: String, previous: String? = null): Topic? {
        if (input.length > 500) return null
        val text = input.lowercase(Locale.ROOT).replace('’', '\'').trim().trimEnd('?', '.', '!')
        if (Regex("^what (?:does|do) .+\\bmean\\b").containsMatchIn(text)) return null
        if (Regex("^(send|call|dial|set|run|execute|approve|confirm|delete|teach|learn|remember|start|stop)\\b").containsMatchIn(text)) return null
        if (text in setOf("and now", "what about now", "and currently") && previous != null) return topic(previous)
        // Self-knowledge must not consume general questions about another product or how to build things.
        if (Regex("^(?:how (?:do|can|should|would)|why|what does|what do)\\b").containsMatchIn(text)) return null
        if (Regex("\\b(?:android|windows|linux|python|chrome|iphone|samsung|git)\\b").containsMatchIn(text) &&
            !Regex("\\b(?:you|your|jarvis)\\b").containsMatchIn(text)) return null
        val question = Regex("\\b(what|which|can|could|are|do|is|does|tell|show|explain|describe|list|installed|current|status|diagnostics|any)\\b").containsMatchIn(text)
        if (!question && text !in setOf("capabilities", "features", "version", "voice diagnostics")) {
            return null
        }
        return when {
            Regex("\\b(version|build|release)\\b").containsMatchIn(text) &&
                (Regex("\\b(you|your|jarvis|installed|current|currently|running)\\b").containsMatchIn(text) || text in setOf("version", "build", "release", "what version", "which version")) &&
                !Regex("\\b(notes|features|changes)\\b").containsMatchIn(text) -> Topic.VERSION
            Regex("\\b(screen|display)\\b").containsMatchIn(text) && Regex("\\b(can|could|able|support|capability|capabilities|access|status|see|read|control|understand)\\b").containsMatchIn(text) -> Topic.SCREEN
            Regex("\\b(voice|wake|microphone|mic|listening)\\b").containsMatchIn(text) && Regex("\\b(diagnos\\w*|timing|latency|delay|status|ready)\\b").containsMatchIn(text) -> Topic.VOICE
            Regex("\\b(self[ -]?diagnos\\w*|self[ -]?improv\\w*|source code)\\b").containsMatchIn(text) -> Topic.DIAGNOSTICS
            Regex("\\b(provider|gemini|model|ai connection|ai connectivity)\\b").containsMatchIn(text) && Regex("\\b(status|configured|connected|selected|available|working|using|use)\\b").containsMatchIn(text) -> Topic.PROVIDERS
            Regex("\\b(features|capabilities|abilities|release notes|what's new|what is new|what changed|what can you do)\\b").containsMatchIn(text) -> Topic.FEATURES
            else -> null
        }
    }
    fun snapshot(context: Context): Snapshot {
        val device = AndroidDeviceReport(context)
        val prefs = context.getSharedPreferences("jarvis_cloud_ai", Context.MODE_PRIVATE)
        return Snapshot(device.app().value, context.packageName == "com.assistant.core", device.accessibility().value,
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED,
            device.contacts().value, device.calls().value, device.notifications().value,
            DirectOnlineClient(context).hasGeminiKey(), FallbackAiClient(context).isConfigured(),
            prefs.getString("gemini_model", GeminiModelDiscovery.DEFAULT_MODEL).orEmpty().takeIf { it.matches(Regex("(?:gemini|gemma|learnlm)-[a-z0-9._-]{1,100}")) } ?: "unverified model ID",
            IntelligenceRouting.selected(prefs.getString("primary_provider", "gemini")), prefs.getBoolean("provider_fallback", true),
            VoiceRuntimeObservation.read(context, VoiceRuntimeObservation.Owner.LOCAL), VoiceRuntimeObservation.read(context, VoiceRuntimeObservation.Owner.FOREGROUND),
            runCatching {
                val text = context.assets.open("installed-build-features.json").use { stream ->
                    ProviderResponseText.read(stream, 16_384)
                }
                val json = org.json.JSONObject(text); require(json.getInt("schemaVersion") == 1)
                val notes = json.getJSONArray("notes"); require(notes.length() <= 16)
                (0 until notes.length()).map { notes.getString(it).also { note -> require(note.length <= 512 && note.none(Char::isISOControl)) } }
            }.getOrDefault(emptyList()), DirectOnlineClient(context).connectionObservation(), FallbackAiClient(context).connectionObservation())
    }
    fun render(topic: Topic, value: Snapshot): String {
        fun permission(granted: Boolean?) = when (granted) { true -> "granted"; false -> "permission/access needed"; null -> "not verified" }
        val version = value.version?.let { "Installed J.A.R.V.I.S. ${it.version ?: "version name unavailable"}, build ${it.code}." }
            ?: "Android's installed-package version could not be read."
        val screen = if (!value.personal) "Supervised Personal screen control is not enabled for this product."
        else "This installed build includes Accessibility screen reading, labelled-control selection, scrolling and bounded supervised tasks. " + when (value.accessibility) {
            DeviceReport.Accessibility.CONNECTED, DeviceReport.Accessibility.REMOTE_CONNECTED -> "The Accessibility bridge is connected now."
            DeviceReport.Accessibility.CONFIGURED -> "Android has enabled Accessibility, but its bridge connection is not verified."
            DeviceReport.Accessibility.OFF -> "Enable Jarvis in Android Accessibility settings before using it."
            null -> "Accessibility availability could not be verified."
        } + " Ask me to read the screen or show screen control status. Protected screens remain blocked; consequential actions require current approval. No screen content was read for this answer."
        fun observed(label: String, result: AiConnectionDiagnostics.Result?) = if (result == null)
            "$label: no fresh connection observation for the current settings. Use Intelligence → Test connection."
            else AiConnectionDiagnostics.render(result, label)
        val providers = IntelligenceRouting.status(value.provider, value.fallback, value.geminiConfigured, value.gatewayConfigured, value.selectedModel) +
            "\n" + observed("Gemini", value.geminiObservation) + "\n" + observed("Gateway", value.gatewayObservation)
        val voice = "Microphone permission: ${permission(value.microphone)}. " +
            listOf("local" to value.localVoice, "foreground" to value.foregroundVoice).joinToString(" ") { (owner, state) ->
                if (state == null) "$owner voice: no fresh live observation." else "$owner voice: wake ${state.wake.name.lowercase()}, session ${state.phase.userLabel()}"
            }
        return when (topic) {
            Topic.VERSION -> "$version ${if (value.personal) "Personal Jarvis update channel." else "Separate Beta application."} Build type: ${com.assistant.core.BuildConfig.BUILD_TYPE}. This is installed-package metadata, not an update-server version."
            Topic.SCREEN -> screen
            Topic.PROVIDERS -> providers
            Topic.VOICE -> voice + "\n" + listOf(value.localVoice, value.foregroundVoice).filterNotNull()
                .flatMap { it.timings }.joinToString("\n") { "${it.stage}: ${it.sinceStartMillis} ms since capture request; ${it.sincePreviousMillis} ms since prior callback" } +
                "\nTiming evidence excludes audio and transcripts; missing callbacks leave latency unverified."
            Topic.DIAGNOSTICS -> "Self Diagnostics reviews bounded source, build and application-error reports. Source scanning runs through the read-only GitHub workflow or an authorized development machine; the app imports and reviews its findings. The independent Jarvis Developer app prepares source-verified proposals. An observation is not permission to edit, merge, sign, publish or install."
            Topic.FEATURES -> "$version " + (if (value.buildNotes.isEmpty()) "Bundled change notes could not be read. " else "Included build changes: " + value.buildNotes.joinToString(" ") + " ") +
                "Installed local tools include time/date, calls and contact lookup, message composition, app launching, alarms/timers, reminders, media/volume controls, connectivity diagnostics and weather. " +
                "Calls: ${permission(value.calls)}; contacts: ${permission(value.contacts)}; notifications: ${permission(value.notifications)}. $screen $voice $providers " +
                "Feature requests and unverified self-generated repairs are not installed capabilities. No whole-phase completion is inferred."
        }
    }
    fun aiContext(value: Snapshot): org.json.JSONObject = org.json.JSONObject()
        .put("installed_version", value.version?.version ?: org.json.JSONObject.NULL)
        .put("installed_build", value.version?.code ?: org.json.JSONObject.NULL)
        .put("product", if (value.personal) "Personal Jarvis" else "Beta")
        .put("screen_support_installed", value.personal)
        .put("screen_access_observation", value.accessibility?.name ?: "UNVERIFIED")
        .put("microphone_permission", value.microphone)
        .put("gemini_configured", value.geminiConfigured).put("selected_gemini_model", value.selectedModel)
        .put("gateway_configured", value.gatewayConfigured)
        .put("gemini_recent_usable_response", value.geminiObservation?.success ?: org.json.JSONObject.NULL)
        .put("gemini_recent_problem", value.geminiObservation?.problem?.name ?: org.json.JSONObject.NULL)
        .put("gateway_recent_usable_response", value.gatewayObservation?.success ?: org.json.JSONObject.NULL)
        .put("gateway_recent_problem", value.gatewayObservation?.problem?.name ?: org.json.JSONObject.NULL)
        .put("provider_health", "Observations are bounded past requests for the current settings, not continuous connectivity")
        .put("source_modification", "Requires explicit source verification and owner approval in separate Developer application")
}
