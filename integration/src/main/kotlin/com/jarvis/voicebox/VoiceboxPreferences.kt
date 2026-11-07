package com.jarvis.voicebox

import android.content.Context

data class VoiceboxConfig(val enabled: Boolean, val endpoint: String, val profile: String)
object VoiceboxPreferences {
    fun load(context: Context): VoiceboxConfig {
        val p = context.getSharedPreferences("voicebox", Context.MODE_PRIVATE)
        return VoiceboxConfig(p.getBoolean("enabled", false), p.getString("endpoint", "http://127.0.0.1:17493").orEmpty(), p.getString("profile", "").orEmpty())
    }
    fun save(context: Context, config: VoiceboxConfig) {
        val endpoint = VoiceboxClient.validateEndpoint(config.endpoint)
        require(!config.enabled || config.profile.isNotBlank()) { "Enter a Voicebox profile name or ID" }
        check(context.getSharedPreferences("voicebox", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", config.enabled).putString("endpoint", endpoint)
            .putString("profile", config.profile.trim()).commit()) { "Could not save Voicebox settings" }
    }
}
