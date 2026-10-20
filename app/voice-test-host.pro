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
# Foundation instrumentation constructs these APIs from the separate test APK.
# Preserve its entry points while optimizing bodies; release/Beta rules are unchanged.
-keep,allowoptimization class com.assistant.core.services.HybridAssistantService** { public *; }
-keep,allowoptimization class com.assistant.core.services.HybridAssistantReply** { public *; }
-keep,allowoptimization class com.assistant.core.services.ConversationMemory** { public *; }
-keep,allowoptimization class com.assistant.core.services.SystemService** { public *; }
-keep,allowoptimization class com.assistant.core.services.AuditService** { public *; }
-keep,allowoptimization class com.assistant.core.engine.ActionRegistry** { public *; }
-keep,allowoptimization class com.assistant.core.engine.IntentClassifier** { public *; }
-keep,allowoptimization class com.assistant.core.engine.PolicyGate** { public *; }
-keep,allowoptimization class com.assistant.core.engine.CapabilityBroker** { public *; }
-keep,allowoptimization class com.assistant.core.engine.CapabilityRouting** { public *; }
-keep,allowoptimization class com.assistant.core.engine.ActionExecutor** { public *; }
-keep,allowoptimization class com.assistant.core.engine.AssistantEngine** { public *; }
-keep,allowoptimization class com.assistant.core.models.ActionResult** { public *; }
-keep,allowoptimization class com.assistant.core.models.CapabilityState** { public *; }
-keep,allowoptimization class com.assistant.core.storage.Database** { public *; }
-keep,allowoptimization class com.assistant.core.storage.ActionRepository** { public *; }
-keep,allowoptimization class com.assistant.core.storage.AuditRepository** { public *; }
# Durable routine acceptance uses these APIs from the separate instrumentation APK.
-keep,allowoptimization class com.assistant.core.workflow.** { public *; }
-keep,allowoptimization class com.assistant.core.learning.ApprovedRoutineRegistry** { public *; }
# Goal/evaluation acceptance APIs used from the separately compiled test APK.
-keep,allowoptimization class com.assistant.core.services.GoalPlanning** { public *; }
-keep,allowoptimization class com.assistant.core.services.GoalPlanStore** { public *; }
-keep,allowoptimization class com.assistant.core.services.PersonalStateFile** { public *; }
-keep,allowoptimization class com.assistant.core.services.ImprovementEvaluation** { public *; }
-keep,allowoptimization class com.assistant.core.services.LocalFoundationChecks** { public *; }
-keep,allowoptimization class com.assistant.core.services.PersonalReminders** { public *; }
-keep,allowoptimization class com.assistant.core.services.PersonalReminderRuntime** { public *; }
-keep,allowoptimization class com.assistant.core.services.PersonalReminderReceiver** { public *; }
-keep,allowoptimization class com.assistant.core.services.ContactCallWorkflow** { public *; }
-keep,allowoptimization class com.assistant.core.services.ResearchBriefs** { public *; }
-keep,allowoptimization class com.assistant.core.services.ChatSearchResults** { public *; }
