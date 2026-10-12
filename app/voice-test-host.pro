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
