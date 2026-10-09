package com.assistant.core.services

import android.content.Context

data class VoiceConfig(
    val enableDedicatedWakeWord: Boolean,
    val porcupineAccessKey: String,
    val wakeSensitivity: Float,
    val autoStartVoice: Boolean,
    val preferOfflineCommandRecognition: Boolean,
    val commandConfidenceThreshold: Float,
    val defaultProjectName: String,
    val useForegroundServiceMode: Boolean,
    val autoStartForegroundService: Boolean,
    val customProjectPhrases: String,
    val customStatusPhrases: String,
    val customShellPhrases: String,
    val customRebootPhrases: String,
    val customSettingsPhrases: String,
    val customStartVoicePhrases: String,
    val customStopVoicePhrases: String
)

class VoicePreferences(context: Context) {

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): VoiceConfig {
        return VoiceConfig(
            enableDedicatedWakeWord = preferences.getBoolean(KEY_DEDICATED_WAKE_WORD, true),
            porcupineAccessKey = preferences.getString(KEY_PORCUPINE_ACCESS_KEY, "").orEmpty().trim(),
            wakeSensitivity = preferences.getFloat(KEY_WAKE_SENSITIVITY, 0.6f),
            autoStartVoice = preferences.getBoolean(KEY_AUTO_START, false),
            preferOfflineCommandRecognition = preferences.getBoolean(KEY_PREFER_OFFLINE_COMMAND, true),
            commandConfidenceThreshold = preferences.getFloat(KEY_COMMAND_CONFIDENCE_THRESHOLD, 0.58f),
            defaultProjectName = preferences.getString(KEY_DEFAULT_PROJECT_NAME, "assistant_demo").orEmpty(),
            useForegroundServiceMode = preferences.getBoolean(KEY_USE_FOREGROUND_SERVICE_MODE, false),
            autoStartForegroundService = preferences.getBoolean(KEY_AUTO_START_FOREGROUND_SERVICE, false),
            customProjectPhrases = preferences.getString(KEY_CUSTOM_PROJECT_PHRASES, "").orEmpty(),
            customStatusPhrases = preferences.getString(KEY_CUSTOM_STATUS_PHRASES, "").orEmpty(),
            customShellPhrases = preferences.getString(KEY_CUSTOM_SHELL_PHRASES, "").orEmpty(),
            customRebootPhrases = preferences.getString(KEY_CUSTOM_REBOOT_PHRASES, "").orEmpty(),
            customSettingsPhrases = preferences.getString(KEY_CUSTOM_SETTINGS_PHRASES, "").orEmpty(),
            customStartVoicePhrases = preferences.getString(KEY_CUSTOM_START_VOICE_PHRASES, "").orEmpty(),
            customStopVoicePhrases = preferences.getString(KEY_CUSTOM_STOP_VOICE_PHRASES, "").orEmpty()
        )
    }

    fun save(config: VoiceConfig) {
        preferences.edit()
            .putBoolean(KEY_DEDICATED_WAKE_WORD, config.enableDedicatedWakeWord)
            .putString(KEY_PORCUPINE_ACCESS_KEY, config.porcupineAccessKey.trim())
            .putFloat(KEY_WAKE_SENSITIVITY, config.wakeSensitivity.coerceIn(0.1f, 1.0f))
            .putBoolean(KEY_AUTO_START, config.autoStartVoice)
            .putBoolean(KEY_PREFER_OFFLINE_COMMAND, config.preferOfflineCommandRecognition)
            .putFloat(
                KEY_COMMAND_CONFIDENCE_THRESHOLD,
                config.commandConfidenceThreshold.coerceIn(0.2f, 0.95f)
            )
            .putString(
                KEY_DEFAULT_PROJECT_NAME,
                config.defaultProjectName.trim().ifBlank { "assistant_demo" }
            )
            .putBoolean(KEY_USE_FOREGROUND_SERVICE_MODE, config.useForegroundServiceMode)
            .putBoolean(KEY_AUTO_START_FOREGROUND_SERVICE, config.autoStartForegroundService)
            .putString(KEY_CUSTOM_PROJECT_PHRASES, config.customProjectPhrases)
            .putString(KEY_CUSTOM_STATUS_PHRASES, config.customStatusPhrases)
            .putString(KEY_CUSTOM_SHELL_PHRASES, config.customShellPhrases)
            .putString(KEY_CUSTOM_REBOOT_PHRASES, config.customRebootPhrases)
            .putString(KEY_CUSTOM_SETTINGS_PHRASES, config.customSettingsPhrases)
            .putString(KEY_CUSTOM_START_VOICE_PHRASES, config.customStartVoicePhrases)
            .putString(KEY_CUSTOM_STOP_VOICE_PHRASES, config.customStopVoicePhrases)
            .apply()
    }

    fun setForegroundServiceRunning(running: Boolean) {
        preferences.edit().putBoolean(KEY_FOREGROUND_SERVICE_RUNNING, running).apply()
    }

    fun isForegroundServiceRunning(): Boolean {
        return preferences.getBoolean(KEY_FOREGROUND_SERVICE_RUNNING, false)
    }

    companion object {
        private const val PREFS_NAME = "voice_settings"
        private const val KEY_DEDICATED_WAKE_WORD = "dedicated_wake_word"
        private const val KEY_PORCUPINE_ACCESS_KEY = "porcupine_access_key"
        private const val KEY_WAKE_SENSITIVITY = "wake_sensitivity"
        private const val KEY_AUTO_START = "auto_start_voice"
        private const val KEY_PREFER_OFFLINE_COMMAND = "prefer_offline_command_recognition"
        private const val KEY_COMMAND_CONFIDENCE_THRESHOLD = "command_confidence_threshold"
        private const val KEY_DEFAULT_PROJECT_NAME = "default_project_name"
        private const val KEY_USE_FOREGROUND_SERVICE_MODE = "use_foreground_service_mode"
        private const val KEY_AUTO_START_FOREGROUND_SERVICE = "auto_start_foreground_service"
        private const val KEY_CUSTOM_PROJECT_PHRASES = "custom_project_phrases"
        private const val KEY_CUSTOM_STATUS_PHRASES = "custom_status_phrases"
        private const val KEY_CUSTOM_SHELL_PHRASES = "custom_shell_phrases"
        private const val KEY_CUSTOM_REBOOT_PHRASES = "custom_reboot_phrases"
        private const val KEY_CUSTOM_SETTINGS_PHRASES = "custom_settings_phrases"
        private const val KEY_CUSTOM_START_VOICE_PHRASES = "custom_start_voice_phrases"
        private const val KEY_CUSTOM_STOP_VOICE_PHRASES = "custom_stop_voice_phrases"
        private const val KEY_FOREGROUND_SERVICE_RUNNING = "foreground_service_running"
    }
}
