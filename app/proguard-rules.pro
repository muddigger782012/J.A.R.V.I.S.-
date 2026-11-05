# sherpa-onnx JNI reflects over Kotlin config/result field names.
# R8 must not rename or remove these members in release builds.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepattributes *Annotation*


# Sherpa 1.13.8 JNI calls invoke([F)Ljava/lang/Integer; on the concrete
# callback object. Keep the Java bridge including its boxed-return method.
# This protects the optimized voiceCheck build AND signed production release.
-keep class com.assistant.core.services.NativeTtsCallback { *; }
