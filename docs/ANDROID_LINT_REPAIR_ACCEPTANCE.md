# Authorized Android lint and compatibility repairs

The owner explicitly authorized repairing the existing errors that blocked full
lint after reviewing the read-only self-diagnostics increment. These repairs stay
on that development PR. Merge, publication and installation require separate
owner authorization; no autonomous repair executor or release authority is added.

## Findings and changes

The earlier full debug lint report contained 13 errors. Several were repeated call
sites, not independent defects. The repair scope preserves existing capabilities:

| Original finding | Change |
| --- | --- |
| Four API 33 package-query errors | One SDK-guarded installed-application query uses typed flags on Android 13+ and the supported legacy overload on older versions; both app-label and Amazon Music paths share it. |
| Two API 31 recognizer errors | Command and live capture share an explicit SDK guard. Unsupported strict on-device capture rejects before the new API; it never silently selects a system provider. |
| Private receiver registration | Main's voice-event receiver uses AndroidX `RECEIVER_NOT_EXPORTED` across supported versions. Genuine events from the app remain supported, including the wake process. Older Android versions use AndroidX's signature-permission protection. |
| Two foreground-service findings | Voice startup now explicitly supplies the microphone type through `ServiceCompat`, with a supported older-SDK path. The existing production microphone declaration and required permissions remain. |
| Two special-permission findings | Preserve `WRITE_SETTINGS` and `PACKAGE_USAGE_STATS`, with exactly two documented `ProtectedPermissions` exceptions on those declarations. Android's existing user-approved Settings/AppOps flows and access checks remain required. No permission is granted automatically. |
| Two optional-hardware findings | Camera and telephony are explicitly optional, retaining camera/call features on capable phones while allowing supported use without that hardware. |

The remaining foreground-service false positive was traced to the exact lint
31.5.2 detector: it reads `context.project.manifestDom` and searches that document
for a typed service. Debug and optimized voice acceptance use a fixture manifest
overlay, while the merged manifest already contained the correct production
microphone type. The overlay now repeats the **actual voice service** declaration
and type. The IPC probe stays a normal service. This avoids a foreground-service
suppression and keeps production/Beta fixture exclusion intact.

The two protected-permission exceptions are deliberate, narrow handling for
supported Android special access. Removing these declarations would break the
existing owner-controlled system-settings and usage-access features. Official
contracts: [write settings](https://developer.android.com/reference/android/Manifest.permission#WRITE_SETTINGS)
and [usage access](https://developer.android.com/reference/android/Manifest.permission#PACKAGE_USAGE_STATS).
There is no lint baseline, global issue disable, lower severity, `abortOnError`
change, SDK requirement increase or removal of existing permission controls.

## Verification contract

- Full `lintDebug` is a required part of the existing CI unit/build job. Its report
  is retained even on failure. Existing warnings remain visible.
- Unit tests, all three debug architectures, instrumentation APK and Beta manifest
  processing must pass. Existing theme, scanner/release checks and UI tests remain.
- A dedicated emulator matrix runs four real Android compatibility cases on API 26
  and API 34. It checks actual package queries without launching an app; supported
  recognizer creation/destruction without starting microphone capture; delivery
  of a genuine private event and rejection of a sender running as the shell UID;
  and actual installed permissions, optional features and voice-service metadata.
- The same compatibility class runs in the optimized full Android suite. The
  strict harness now requires 15 preflight and 65 full cases. Its 120/360-second
  deadlines are unchanged; the dedicated four-case matrix has its own bounded
  runner. No skipped cases count as acceptance.
- The publication verification gate explicitly requires UI and both compatibility
  matrix jobs in addition to unit/build/lint, gateway, voice/Accessibility and
  theme evidence. Missing, failed and skipped matrix/UI jobs are rejected by
  release regression tests. This adds checks without granting publication rights.
- The read-only source-analysis workflow runs for all app-source PR changes,
  allowing a fresh bounded report after a repair without running source content
  or granting any write approval.

The current verified commit, test results and CI links are recorded in
[the development PR](https://github.com/muddigger782012/J.A.R.V.I.S.-/pull/212).
Microphone acoustics, actual background/OEM behavior, special-access approval
screens, installed-data upgrades and live provider connectivity still need real
phone checks. Creating/destroying a recognizer is not a speech-recognition pass.
The compatibility test never starts a microphone, voice pipeline, call or app.

The next self-improvement milestone remains a Personal source proposal in Jarvis
Developer with an exact revision/path/diff scope, explicit approval and renewed
approval after any material change. These owner-authorized development repairs do
not mean the Personal app can yet implement its own approved repairs.
