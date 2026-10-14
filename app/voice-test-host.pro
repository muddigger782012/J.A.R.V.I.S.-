# APIs referenced by the separately compiled instrumentation APK must survive
# the target R8 pass. Bodies can still be optimized; production release rules
# are unchanged. Kotlin support is needed by AndroidX test runner.
-keep class kotlin.** { *; }
-keep,allowoptimization class com.assistant.core.services.NeuralVoiceCatalog** { public *; }
-keep,allowoptimization class com.assistant.core.services.AssistantVoicePreferences { public *; }
-keep,allowoptimization class com.assistant.core.services.AssistantVoiceProfile { public *; }
-keep,allowoptimization class com.assistant.core.services.AssistantVoiceSelection { public *; }
-keep,allowoptimization class com.assistant.core.services.NeuralAssistantSpeech { public *; }
-keep,allowoptimization class com.assistant.core.services.NeuralVoiceDiagnostics { public *; }
-keep,allowoptimization class com.assistant.core.services.NeuralSpeechCache$Clip { public *; }
-keep,allowoptimization class com.assistant.core.services.NeuralPcmPlayer { public *; }
# AndroidX instrumentation shares dependencies with the target APK. Preserve
# their public runtime surface across the separate R8 passes.
-keep,allowoptimization class androidx.** { *; }
-keep,allowoptimization class com.assistant.core.services.NeuralVoicePack { public *; }
# AGP emits non-final resource IDs; instrumentation references their fields.
-keep class com.assistant.core.R$* { *; }
# Recognition-profile UI regression references these APIs in the optimized target.
-keep,allowoptimization class com.assistant.core.services.VoiceProfileStore { public *; }
-keep,allowoptimization class com.assistant.core.services.VoiceRecognitionHints { public *; }
# Settings instrumentation directly calls these helpers in the separately compiled test APK.
-keep,allowoptimization class com.assistant.core.FontPreferences { public *; }
-keep,allowoptimization class com.assistant.core.ThemePreferences { public *; }
-keep,allowoptimization class com.assistant.core.SettingsMenuViews { public *; }
