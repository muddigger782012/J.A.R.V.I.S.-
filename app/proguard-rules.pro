# sherpa-onnx JNI reflects over Kotlin config/result field names.
# R8 must not rename or remove these members in release builds.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepattributes *Annotation*


# Sherpa 1.13.8 native callback calls invoke([F)Ljava/lang/Integer; on
# the concrete object. R8 may remove that typed bridge on a Kotlin lambda.
# Preserve only our stable Java JNI adapter in both release and voiceCheck.
-keep class com.assistant.core.services.NativeTtsCallback { *; }
