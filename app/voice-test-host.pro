# Test-runner-only support: prevent the optimized target APK from removing
# Kotlin APIs which the separately packaged AndroidX instrumentation uses.
-keep class kotlin.** { *; }
