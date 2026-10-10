# sherpa-onnx JNI reflects over Kotlin config/result field names.
# R8 must not rename or remove these members in release builds.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepattributes *Annotation*

