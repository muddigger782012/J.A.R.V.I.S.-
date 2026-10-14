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

## Standalone APK / product boundary decisions

Jarvis is **one standalone AI assistant APK**, not a launcher or operating system. The wider commercial ecosystem may eventually contain separate APKs. Jarvis Keyboard **already exists** and must be treated as an independent product, not recreated or merged into Jarvis.

For each significant feature request or implementation, assess and report one of:
1. **Core Jarvis**: assistant conversation, intelligence, context, wake word, speech, personality, and ordinary assistant-driven device actions; keep in the Jarvis APK.
2. **Shared technology**: reusable functionality exposed behind a stable interface, but not necessarily a separate APK; avoid needless process boundaries.
3. **Standalone APK candidate**: a distinct user-facing workflow with its own lifecycle, permissions, UX, update cadence, or potential commercial market. Present rationale and tradeoffs to the user **before** splitting.

Document the proposed classification in PR descriptions for significant features, including: dependencies, APK/download-size impact, runtime/memory/battery cost, update compatibility, privacy/security permissions, and commercial potential. The user approves product splits; do not automatically create APKs or repositories.

- **Jarvis Keyboard**: maintain its existing standalone APK, signing identity, updater, installation and data. Share AI/voice contracts only through optional, versioned integration; keyboard must remain useful if Jarvis is absent or offline. Do not assume keyboard source lives in this repository.
- **Jarvis Launcher/UI**: a future independent APK candidate; do not expand Jarvis Assistant into a launcher.
- **Specialized diagnostics**: possible future independent product, not a reason to expand the assistant's default permissions.
- **Cloud gateway**: shared service, not an Android APK.
- Never add a mandatory cross-app dependency or broad system permissions solely to enable ecosystem reuse.

## Codex implementation efficiency

- Follow `docs/CODEX_WORKFLOW.md`, `docs/DEVELOPMENT_BACKLOG.md`, and `docs/TESTING_BUILD_POLICY.md` for all substantial coding tasks.
- Use `.github/ISSUE_TEMPLATE/codex-task.md` to specify one approved, bounded implementation with acceptance criteria, dependencies and test plan.
- Check open related PRs and avoid duplicating or overwriting ongoing work. Prefer narrow searches, small cohesive patches and relevant targeted tests during iteration.
- Never trade away security, test coverage or upgrade compatibility to save usage. Report failing checks and physical-device verification separately.
- End each task with changed files, PR, tests, unresolved blockers and next recommended task.
