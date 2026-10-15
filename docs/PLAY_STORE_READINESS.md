# Dual-channel Android distribution: Play Store readiness

Status: **approved architecture target; not yet implemented or Play-approved**.

## Distribution targets
- Direct: retain existing signed Jarvis and Beta APKs, package names, version-code monotonicity, update endpoints, user data and functioning in-app updater.
- Play: build an Android App Bundle (AAB) from the same core source, with Play-compatible update flow, permissions, disclosures and release signing.
- Prefer product flavors/build variants for channel-specific policy and update handling; do not fork assistant logic or create an additional Jarvis application.
- Keep Jarvis Keyboard a separate product and preserve its existing identity and update feed.

## Required implementation plan
1. Inventory existing Gradle variants, signing fingerprints, manifest permissions, install/update code, target SDK, APK/AAB sizes, voice assets and Play-sensitive services.
2. Define direct and Play distribution variants. Use Play In-App Updates or Play-managed updates in the Play channel; no self-install APK updater or unnecessary REQUEST_INSTALL_PACKAGES permission in that variant.
3. Generate release AAB and direct signed APK in CI with separate artifact names, signing configuration and channel-specific validation. Protect keys in CI. Do not assume Play App Signing and direct signing keys are interchangeable; document migration and update compatibility.
4. Audit AccessibilityService usage, background microphone/wake-word behavior, foreground services, overlays, notifications, data collection and permissions against current Google Play policies. Preserve direct functionality where lawful; redesign Play behavior where required. No assertion of policy approval without review.
5. Track compressed delivery size per variant. Aim for a small core (internal target under 100 MB, not a Play hard cap); plan optional voice/AI assets via verified on-demand downloads or Play Asset Delivery where appropriate, including offline-first behavior and integrity checks.
6. Add automated checks for package IDs, versioning, manifest permissions, AAB validity, signature compatibility, update routing, and direct in-place upgrades. Validate Play internal testing before production.
7. Prepare Play Console listing, privacy policy, Data safety form, account/data deletion where applicable, permission declarations, tester rollout and crash reporting.

## Release gates
- Existing direct APK updates and Beta behavior remain functional.
- Play variant installs and updates using Play-supported distribution mechanisms.
- No secrets embedded in commercial APK/AAB. Cloud access authenticated and metered.
- Automated CI and physical-device verification recorded independently.
- No new release is described as Play-ready until required policy and internal-test checks pass.
