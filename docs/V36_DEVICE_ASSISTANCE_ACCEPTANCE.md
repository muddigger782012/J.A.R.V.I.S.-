# Intelligent device assistance acceptance

This tracks the Personal Jarvis v3.6 initiative. Planning estimates are not measured
completion percentages. Installed-data, real-phone voice/HUD and live-provider
acceptance remain separate from unit and emulator evidence.

## Release infrastructure delivered

Personal 3.5.496 signed downloads are public. The newer 3.5.498 updater was promoted
without manually preparing a manifest or release artifact. Production identity,
all three signed architectures, native/wake assets, deterministic ZIP, checksums,
uploaded bytes, public downloads and actual raw updater bytes were verified.
Historical publication preserved the newer updater rather than downgrading it.
The first draft verification exposed an unpublished-tag lookup error; the corrected
publisher validates the stable release record and verifies draft bytes before
publication. Regression tests and actual draft/public download verification passed.

## Screen intelligence and supervised tasks

| Capability | Implemented behavior | Evidence required |
| --- | --- | --- |
| Foreground screen | Resolve application name from Android; inspect bounded, refreshed visible nodes | Actual Accessibility fixture/window identity |
| Semantic controls | Labels, Android roles, headings/sections, disabled and checked state | Native buttons, duplicate sections, disabled control |
| Selection | Exact labels, typed role/section qualifiers and current numbered choices | No first-match ambiguity guessing |
| Task planning | Up to four local label-based taps within one app, with an explicit final visible-text criterion | Review/clarification without dispatch |
| Authorization | Exact plan approval, then a distinct numbered confirmation for each freshly reviewed target | Bare yes and old step confirmations cannot tap |
| Delayed approvals | Bind prepared confirmations to the reviewed target generation | Replacing a target on the same screen rejects the older confirmation |
| Verification | After an accepted tap, attempt bounded local observation; offer the next reviewed target or check final text | Actual page transitions and exact visible-text predicate |
| Recovery | Continue only inspects/reviews; changed, missing, protected or uncertain states never replay a tap automatically | Failure, expiry, reconnect, unexpected-page cases |
| Context | Preserve task intent across a pause; unrelated conversation keeps its own confirmation context | No generic yes taken from another tool |
| Cancellation | Invalidate queued requests before dispatch; voice/chat, themed Tools dialog and private notification cancel action | Blocked-UI cancellation, no later click, actual PendingIntent receiver |
| Privacy | Screen values stay outside assistant/provider memory and learning/audit datasets | Local routing and password-window exclusion |

Example: `help me open Menu then Downloads and verify Downloads`.
If the requested text is absent, Jarvis reports that verification is still pending.
Android accepting a request is not proof of message delivery, a purchase, a deletion
or another app-side transaction. Every tap still needs the user's current approval.
There is no arbitrary typing, coordinate injection, cross-app UI macro or automatic
resume after process death. Existing durable approved routines remain available.

Task controls: `review screen task`, `approve screen task`, `confirm screen step
one`, `continue screen task`, `screen task status`, `change screen step to ...`,
`use task control two`, and `cancel screen task`. Task intent expires after five
minutes; individual tap reviews retain their shorter existing expiry. A paused task
needs a fresh target review before confirmation. Cancellation cannot undo an Android
action already submitted. Notification cancellation is available when Android
allows Jarvis notifications; the themed Tools dialog and typed/voice controls remain.

Locked, recognized password-entry, system/keyboard, Android Settings, permission,
installer and security windows stay protected. Accessibility enabling is a manual
Android flow. Debug/optimized acceptance fixtures and their separate-process probe
are excluded from Personal release and Beta APKs. Existing visible chat-history
settings still apply to replies displayed in chat.

## Current verification

The latest local build passed 646 JVM cases across 102 suites with no failures,
errors or skipped cases, all debug architectures, instrumentation compilation and
Beta manifest processing. Release/archive tests and the UI theme contract pass.
The cancellation foundation passed the real-service preflight and the full
optimized emulator suite. Its disposable emulator now resets only the stale launcher
and requires the fixture's own foreground package; app-under-test failures remain
fatal. Screen-role/task/notification acceptance adds two cases and requires both
the four-case preflight and all 47 optimized cases before delivery. Those new
emulator results and signed publication remain pending until recorded explicitly.

## Remaining initiative work

- Task-aware voice follow-up, stale response cancellation and offline capture policy.
- Unified Personal provider selection/fallback while preserving manual model controls.
- Android connectivity diagnostics and supported system-setting handoffs.
- Separate Connection Manager integration using its actual package/interface. No
  manager-specific endpoint or capability has been invented; repository/package
  information has been requested from the owner.
- Real-phone wake/voice/HUD focus, other apps/OEMs, lock/permission transitions,
  installed-data upgrade, and configured live-provider checks.

No broad visual-layout or physical-phone pass is inferred from compilation or CI.
The HUD is retained; the new task dialog reuses the active theme and font settings.
Personal developer controls and Beta/Developer product separation remain required.
