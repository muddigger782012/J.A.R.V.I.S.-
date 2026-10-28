# Screen read recovery and diagnostics acceptance

This Personal increment follows the signed voice/offline release. It adds local
screen-read recovery guidance and the exact voice/chat commands `screen read
diagnostics`, `screen read status` and `screen control diagnostics`. Existing Chat,
HUD, utility navigation, settings and AI provider/model controls are preserved.

## Supported behavior

- Capture failures distinguish a reached deadline, changing tree, lost
  Accessibility connection, cancellation, supported-size limit and unavailable
  Android data. Replies recommend a fresh read, a smaller section or checking
  Android access according to the actual fixed reason. Arbitrary exception
  messages and causes are never included in replies or diagnostics.
- Diagnostics inspect no screen and dispatch no Android action. They distinguish
  current Accessibility connectivity from a historical read outcome, stage,
  bounded node count, elapsed time and Android cache mode.
- The platform keeps one immutable diagnostic in memory. It retains no screen
  text, app/control labels, package identities, node references, exception payloads
  or approval tokens. A later read replaces it; a new platform starts empty.
  Protected reads redact node counts as well as content. Existing user-visible
  chat-history settings still apply to replies displayed in Chat.
- Failed reads consume direct tap reviews and numbered choices. Task failures
  pause unattempted steps or retain result-observation state after an accepted
  step. Diagnostics never advance a task, approve a target or repeat a tap.
- Cancellation/deadline checks also run between child queries on wide trees.
  An Android query already submitted cannot be interrupted by this check, but no
  further child query should start after cancellation is observed.

The existing complete-tree rule, single changed-tree recapture, request/capture
deadlines, node/text/depth limits, private same-UID bridge, protected-screen checks
and exact per-target approvals remain required. No screen action is retried.
This change does not establish the cause of the earlier intermittent uncached
Connection Manager read failure or guarantee screen availability on a busy phone.
It makes future failures diagnosable without recording screen content.

## Verification gates

JVM checks cover fixed recovery reasons, exception-content exclusion, bounded and
historical metadata, protected redaction, read-free diagnostics, reserved local
commands, consumed approvals and task recovery without repeated dispatch.

Android acceptance exercises actual expired and cancelled captures before node
access, successful native reads, the separate-process command bridge, protected
content exclusion and the independent Connection Manager with caching disabled.
An isolated real-assistant fixture checks that diagnostic commands stay local and
leave conversation memory and execution adapters untouched. Acceptance fixtures
remain excluded from production and Beta APKs.

Local verification passes 688 JVM cases across 108 suites with no failures, errors
or skips, all three debug architectures, instrumentation Kotlin and Beta manifest
processing. Theme, 22 release/archive checks and script syntax pass. Branch and
merged-source Android runs passed all 13 preflight and all 59 optimized acceptance
cases. UI emulator, gateway and theme checks passed. No capture deadline, size
limit, privacy boundary or action assertion was relaxed.

Personal [3.6.517 is published](https://github.com/muddigger782012/J.A.R.V.I.S.-/releases/tag/jarvis-3.6.517)
through the existing updater. Production signing, native/wake assets and all three
architecture APKs passed verification. The retained publication receipt verifies
release downloads and the updater. An independent download of the exact default
updater matched its manifest checksum, Git blob, Personal identity and prior
production signing certificate. Acceptance fixtures are absent, and the production
APK contains the specific recovery and content-free diagnostic paths.

- [Implementation and review](https://github.com/muddigger782012/J.A.R.V.I.S.-/pull/210)
- [Branch Android, unit, gateway and UI checks](https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/39956693856)
- [Merged-source Android, unit, gateway and UI checks](https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/39958282039)
- [Production build and signing](https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/39958282017)
- [Automatic publication and retained receipt](https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/39959459938)
- [Download the updater APK](https://raw.githubusercontent.com/muddigger782012/J.A.R.V.I.S.-/main/artifacts/app-debug.apk)

Phone/OEM behavior, real microphone/deep-voice quality, installed-data upgrades and
live Gemini access remain separate. Earlier uncached-read failures are not claimed
fixed solely because controlled checks passed; the new diagnostic identifies the
read outcome and stage without retaining screen content.
