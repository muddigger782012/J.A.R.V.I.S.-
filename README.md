# J.A.R.V.I.S.-
A.I. ASSISTANT 

## Standalone Android use

The APK's built-in phone commands, arithmetic/date answers, voice/TTS, and direct US weather do not require a custom gateway or a companion assistant app. Phone features depend on normal Android permissions and the target phone's apps/services. General AI optionally uses the user's own Gemini key, encrypted in Android Keystore; live weather and Gemini require internet access. Offline speech availability depends on the Android recognizer/language installed on the device.

Home Assistant and Mycroft connections are opt-in under Voice Settings → Assistant connections; leaving that page empty does not block the core assistant. They run on a separate server and do not require companion apps on the phone. Google Assistant's experimental SDK is excluded because this build may be offered publicly. Android App Actions declarations and launcher shortcuts are included; Google invocation requires preview/review separately.

No private API keys or user-specific server credentials are bundled. The signed APK still uses this repository's existing update/signing infrastructure. This is not a declaration that the app has passed Play Store or public-release review. Setup details for optional servers are in `cloud-backend/README.md`.
