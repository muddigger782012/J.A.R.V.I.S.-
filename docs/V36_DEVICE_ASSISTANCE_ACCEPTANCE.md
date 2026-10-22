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

## Conversational voice and hybrid intelligence

A voice-driven screen review can request one follow-up capture without repeating the
wake phrase. Silent or failed wake/follow-up captures return to the dedicated wake
engine when background voice was enabled. A standalone push-to-talk follow-up does
not enable background wake listening. Microphone interruption stops current speech and invalidates late AI replies
without discarding an already delivered approval. An explicit stop cancels pending
AI interpretations. Each recognizer owns a generation; callbacks from stopped or
replaced recognizers cannot become commands. Screen confirmations and continuation
are bound to the review that existed when capture began, including live chat.

Personal's Weather and AI dialog retains API key/model discovery/custom model
controls and adds a primary conversation provider and an optional alternate-provider
fallback. Supported local commands run first. Gemini and the user's HTTPS gateway
are the supported conversation interfaces; gateway models remain server-configured.
Attachments and Google Search grounding still use their dedicated Gemini interface.
`AI routing status` is local and distinguishes configured providers from verified
reachability. Failed/cancelled provider results cannot supply lessons or revive a
proposal. Beta's routing and subscription configuration are unchanged.

## Separate Connection Manager

The existing module and package were verified on the owner's Connection Manager
branch. The reference used for acceptance is pinned to its reviewed source revision.
The manager exports a normal launcher activity and no remote command service or
provider. Jarvis therefore discovers its actual package and asks for a reviewed app
choice before opening it. No credentials, arbitrary intents or remote commands are
sent. The independent application is built and installed alongside the optimized
assistant in CI, without adding it to Personal's production APK or modifying its UI.

Local `connection status`, `Wi-Fi status`, `Bluetooth status`, `mobile data status`
and `VPN status` use standard Android observations. Missing permission, unavailable
hardware and unknown measurements remain explicit. No scan, identifier disclosure,
network probe, Bluetooth pairing, privileged toggle or automatic permission grant
is involved. Change requests open the corresponding Android settings; the user
performs and approves changes there. A visible VPN transport is distinct from proof
of tunnel health or coverage of all profiles. Cellular data state is distinct from
whether the cellular radio is enabled.

## Current verification

The screen-intelligence and supervised-task increment passed its unit/build checks,
its actual Accessibility preflight and the full optimized Android acceptance suite.
Real page transitions, section-qualified duplicate labels, disabled controls,
notification cancellation and replacement-review rejection passed. Android exposes
some styled buttons in uppercase; the fixture assertions now use the same case
normalization as target selection, with roles and section identity still checked.
The verified increment is merged and included in the published Personal v3.6 series.

The combined feature code passed 663 JVM cases across 104 suites with no failures,
errors or skipped cases, all debug architectures, instrumentation compilation and
Beta manifest processing. Release/archive tests and the theme contract pass. The
independent Connection Manager reference APK also builds locally. Final integrated
Android emulator results must be recorded before the v3.6 release is promoted. The integrated acceptance gate requires
all five real-service cases plus the two conversation regressions in preflight,
and all 50 optimized Android cases, including
launching and reading the separate manager while its provider checks remain unrun.
API 34 sensitive views hidden by Android remain inaccessible. If Android supplies
an explicitly marked sensitive node, Jarvis blocks that snapshot; password views
also block surrounding text and controls. Acceptance verifies that a marked value
and button are neither exposed nor tapped, while public surrounding text may remain
readable. A missing node during capture invalidates the snapshot rather than
publishing a partial tree. These additions require the integrated real-service pass.

The next reliability increment gives the notification cancel broadcast foreground
delivery priority and binds its immutable PendingIntent to the original task.
Delayed cancellation cannot discard a replacement task or its notification.
Acceptance sends both an old and current actual notification action and checks
that only the current task is cancelled. Global voice/UI cancellation still
invalidates queued actions without waiting for the main looper.

A disappearing root, window or child invalidates the complete screen capture.
Snapshot reads may attempt one fresh capture within the existing cancellation and
request deadlines. Partial trees are never returned; changing screens that still
cannot be captured fail closed. Action dispatch is never retried. The UI smoke
job similarly retries missing observation dumps within a bound, navigates the
visible Tools/Support menus and requires every destination to open. It no longer
searches hidden legacy header buttons or silently skips missing destinations.

## Release acceptance and practical limits

The v3.6 candidate must pass unit/build checks, the actual Accessibility and optimized
Android acceptance jobs, theme/gateway checks, production signing/native/wake asset
verification, and public APK/updater byte verification. CI checks local provider
ordering/failure/cancellation with controlled responses; configured live-provider
availability requires the owner's API access and is not inferred from CI.

Phone/OEM wake quality, actual microphone/voice recognition, background restrictions,
physical HUD appearance and installed-data upgrade still need a real phone. Existing
configuration/data namespaces and the production signing identity are preserved;
no destructive migration or visual redesign is included. No physical-phone pass or
measured completion percentage is inferred from compilation or emulator results.
