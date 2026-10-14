# J.A.R.V.I.S. visual development rules

All new UI and visual changes must follow the user's currently selected theme.

- Read `docs/UI_THEMING.md` before changing visual code or resources.
- Use `jarvis_*` theme attributes for surfaces, text, icons, borders, highlights and corner geometry. Resolve them from the displayed themed context; do not pin a feature to Default, Stark or Cybertron.
- New activities must extend `ThemedActivity`. App-owned dialogs must use `ThemedDialogBuilder`, which preserves show/dismiss listeners and applies font preferences.
- Reuse the composer icon backgrounds for matching toolbar/header buttons. Popup surfaces must use the active panel colors, stroke and `jarvis_corner`, with readable opaque backing.
- Respect font preferences, large text, scrolling, narrow screens, touch targets and content descriptions. Keep JarvisLabelView and ArcReactorView artwork intentional.
- Run `python3 scripts/check_ui_theme.py` before submitting visual changes. Do not expand its legacy baseline to hide new violations: migrate touched styling to theme attributes instead.
- Verify Default, Stark, Cybertron, their alternatives and the additional palettes. Report source/build checks separately from actual device or emulator visual verification.
- Android-owned permission, installer and picker windows follow Android's styling. Do not promise control over their appearance.

These requirements apply to future additions as well as modifications.


## Commercial architecture and reusable product development

The project is evolving into a reusable software platform for Jarvis, Jarvis Beta, Jarvis Keyboard, and future independent applications. Commercial preparation must be incremental, with no wholesale rewrite and no regression of existing personal installs.

- Before substantial feature changes, identify whether code belongs in reusable assistant/domain services, Android device integration, presentation, or product-specific configuration. Prefer narrow interfaces and small independently testable extractions; do not introduce empty modules or churn solely for architecture.
- Preserve `com.assistant.core` for existing Jarvis installs and `com.assistant.core.beta` for Beta. Preserve signing identity, version-code monotonicity, update URLs, installed data, and backward-compatible behavior. Do not rename packages or move persistent storage without an explicit migration and upgrade tests.
- Keep personal Jarvis, Beta, and future commercial editions distinct through explicit configuration and release-channel policy. Do not overwrite or silently alter existing embedded API integrations during migration; inventory each integration, add a compatible gateway path, and switch only after testing and approval.
- Treat provider credentials embedded into an APK as recoverable, including values supplied via GitHub Actions secrets. Do not add new embedded provider secrets. Commercial AI traffic must ultimately use a server-side credential with authenticated, per-user quotas, abuse controls, and cost accounting. Preserve existing behavior until a verified transition is available.
- Separate entitlements from features through a testable interface. Do not introduce paywalls into the personal or Beta builds, or implement billing without an approved product policy and end-to-end tests.
- Every meaningful new feature or refactor must evaluate reusability, security/privacy, CPU/memory/battery/storage impact, cloud inference cost, and update compatibility. Prefer on-device execution where reliable and economically appropriate.
- Require CI unit, backend, theme, voice, and emulator UI checks relevant to changed code. Never bypass a failing check to claim release readiness; diagnose flaky emulator infrastructure separately from app regressions.
- For changes affecting distribution, verify clean install, upgrade from a previously published APK, signature/package identity, preserved user data, correct channel-specific updater target, and rollback/recovery behavior.
- Record planned module boundaries and migration steps in `docs/COMMERCIAL_ARCHITECTURE.md`. Keep proposed work separate from implemented work and report build, emulator, and physical-device verification independently.
