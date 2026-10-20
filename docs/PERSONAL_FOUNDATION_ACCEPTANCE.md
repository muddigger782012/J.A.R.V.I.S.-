# Personal J.A.R.V.I.S. foundation acceptance

Tracks [the six-phase initiative](https://github.com/muddigger782012/J.A.R.V.I.S.-/issues/140)
and its phase and delivery issues. This increment improves working behavior across
phases without using Phase 1 perfection as a gate. The requested percentages remain
planning targets, **not verified completion measurements**.

Initial publication record: the owner explicitly requested immediate publication
of full personal build 3.5.434 after automated checks passed. That production-signed
foundation build was delivered through the existing personal updater. Physical phone, live-provider and
installed-data upgrade acceptance remain unperformed follow-up checks; they are
not represented as passed. No Beta or Developer release is part of this publication.

## Follow-up: personal routine recovery

Personal build 3.5.435 delivered this verified recovery increment through the
existing updater and versioned downloads. Build 3.5.440 retains it and adds the
Phase 5 and 6 follow-up below; physical acceptance remains follow-up work.

This increment replaces the personal service's session-only routine
adapter with a bounded, atomic journal outside Android backup. Typed chat and voice
share an operating-system execution lease. A competing instance can inspect the
write-ahead checkpoint and save a cancellation request, but cannot dispatch or
recover another live instance's action. The lease is released on process death.

Status reconstruction does not run actions. An interrupted in-flight step stays
ambiguous and cannot be retried. A checkpoint between verified steps becomes paused;
only an explicit resume can run remaining safe steps. An approved checkpoint saved
before first dispatch also needs an explicit invocation or resume. Saved approval,
frozen app targets, outcome evidence and attempt limits remain in force. Generic
session feedback cannot confirm a newer invocation with the same name and target.

The existing exact routine controls now refer to the current execution on this
device across service restarts. Cancellation is cooperative and stops later steps;
it cannot undo an action already dispatched. Cancellation survives the requesting
or executing instance being reconstructed. Corrupt or oversized checkpoints stop
execution rather than falling back to an empty journal. Storage remains limited to
100 retained executions and 32 invocation keys per execution, with no silent
eviction of duplicate-protection evidence. The reviewed history-management follow-up
below allows explicit cleanup while preserving invocation fingerprints.

Acceptance for this increment includes an independent-JVM lease/death test, shared
coordinator races, interrupted and between-step recovery, saved cancellation,
stale feedback, removed approval and corrupt storage tests. Two additional Android
cases exercise the actual atomic files and reconstructed service. The follow-up
pull request records the full local suite (423 tests across 79 suites), all debug
ABI and test APK builds, and successful final-source CI unit/build, gateway, theme
and optimized emulator acceptance (11 tests). The signed build and independent
downloaded-APK verification passed for all supported ABIs. Broad UI-layout smoke
remains skipped upstream. This work adds no background scheduler or generic goal
planner. Physical-device acceptance remains outstanding.

## Phase 5 and 6 follow-up: reviewed goals and supervised evaluation

Personal build 3.5.440 delivers these three bounded offline goal templates and
supervised evaluation through the existing personal updater and versioned signed
downloads. The owner authorized publication after automated verification, with
physical execution, live-provider quality and installed-data upgrade checks pending.

| Goal request | Proposed steps | Acceptance boundary |
| --- | --- | --- |
| `help me focus for 25 minutes` | Set media volume to the visibly proposed 25%, then request the specified timer. A missing duration is clarified across voice/chat instances. | Exact `approve goal plan` required; a bare yes, review or restored conversation cannot execute. Timer acceptance remains a request until outcome evidence is supplied. |
| `prepare a music session` | Propose media volume 35% and open the saved music app, or the exact installed app named with `using ...`. | Bind one launchable target before review; missing or ambiguous apps require a preference or explicit installed name. Playback remains a user step inside the app. |
| `prepare a navigation session` | Propose media volume 50% and open the saved maps app, or an exact installed app named with `using ...`. | The review does not claim route planning or navigation has started. Choose a destination in the opened app. |

An explicit `at 20 percent volume` overrides the visible template suggestion. The
planner composes existing volume/timer/app handlers; it is not a general goal
decomposer, scheduler or arbitrary tool executor. No named routine or learned alias
is created. The same shared execution lease and durable checkpoint engine protect
these goals. Another unresolved routine blocks approval without consuming the new
goal review. A fresh exact approval can recover an approval-save failure only if
no step was attempted. Cancellation/feedback for a stale goal cannot alter a newer
execution. A failed replacement request invalidates the old pending review.

Controls: `review goal plan`, `approve goal plan`, `cancel goal plan`, `goal plan
status`, `resume goal plan`, `the goal worked` and `the goal failed`. Pending review
and duration clarification expire after two minutes; another topic invalidates
them. One current approved definition is retained independently of the routine
registry so an interrupted execution can be reviewed/recovered. All execution
effects still require explicit authorization and existing Android permissions.
Goal dialogue uses the existing bounded conversation window so it becomes the
current topic; that dialogue never supplies execution approval. Evaluation
management/report replies remain outside conversational memory.
Each assistant instance binds approval to the exact goal it displayed. If another
voice/chat instance replaces the plan, or the reviewing instance restarts, the
current steps must be presented again before a subsequent approval can execute.

Phase 6 adds explicitly enabled manual evaluation, separate from automatic
read-only recurrence analysis of the existing outcome journal. Recording is off
by default. The fixed synthetic dataset covers corrections, natural conversation,
initial-only Sir, Jarvis identity, Maps discovery, realistic update status, goal
approval and routine recovery. The owner-reported positive conversational example
from the Phase 6 issue supplies coverage ideas, never an invented test pass; no
raw screenshots, transcripts or provider responses are imported or collected.

Commands:

- `review improvement evaluation`
- `enable improvement evaluation` / `disable improvement evaluation`
- `record improvement feedback for correction handling as passed` (also `failed`
  or `unclear`, using a scenario listed by review)
- `save improvement baseline` / `compare improvement evaluation`
- `prepare improvement proposal for correction handling`
- `clear improvement evaluation`

Records contain only scenario/outcome enums, app build and report time. The latest
explicit report for each scenario/build replaces that report; repeats do not
become independent evidence. At most 96 reports are retained, plus one explicit
baseline of up to eight cases. Comparisons pair only matching retested scenarios,
preserve regressions and uncertainty, and describe owner reports rather than
measured reliability, proven root causes or causal effects of a release. Disable
stops recording while retaining review; clear deletes reports/baseline and turns
recording off. State uses fresh, locked atomic files outside Android backup.

A developer brief links one failed/unclear report to its synthetic reproduction,
expected behavior, relevant modules, proposed failing/positive/ambiguous regression
cases, before/after evaluation, reviewed PR/CI/signing delivery and recovery limits.
It does not fabricate a patch, executed tests, PR or deployment. The app has no
configured GitHub write integration, and its existing updater read token is never
repurposed. Actual patch preparation/developer integration and live-model quality
evaluation remain future work. Clear evaluation leaves goals, conversation,
preferences, learned commands and the result journal separate.

Verified evidence for build 3.5.440: all 445 JVM tests across 82 suites, all supported
debug ABI and test APK builds, final-source CI unit/build, gateway, theme and all
14 optimized Android acceptance tests passed. The production-signed personal build
and independent package/version/signer, ABI, ZIP, native-runtime and wake-asset
checks passed for every APK. Broad UI-layout smoke remains skipped upstream.
The follow-up pull request records these specific contracts:
planner/input boundaries, shared approval/expiry state, failure recovery, duplicate
and stale-control prevention, opt-in/reset, bounded reports, matched comparisons,
concurrent updates and explicit-only developer briefs. Three new Android cases
exercise goal review/clarification, cross-instance review binding and evaluation/baseline state through reconstructed
assistant services. These tests do not establish live-provider naturalness or
physical-device execution/upgrade acceptance. No phase percentage is claimed.

## Phase 5 and 6 follow-up: reviewed history and saved proposals

Personal build 3.5.441 delivers two local management flows through the existing
updater and versioned signed downloads, preserving themes, provider selection, saved
routines and permissions. Physical-phone acceptance remains pending. Previous release
evidence above applies to its named release only.

`review task history` displays retained/eligible counts and groups the exact older
completed records by name and outcome. `clear reviewed task history` must immediately
follow that review in the same assistant instance within two minutes. A different
topic, a new service instance, expiry or any changed journal snapshot invalidates
the review. Cleanup holds the existing execution lease, never runs an action, and
retains the latest status plus every unfinished, failed, running or uncertain task.
Cancellation cannot make an uncertain effect eligible for removal. Only verified,
user-confirmed, or cancelled-without-uncertain-effects older records are candidates.

Cleanup atomically replaces removed records with SHA-256 fingerprints of their
workflow IDs and invocation keys in the same journal. These fingerprints prevent
an old invocation or obsolete writer from resurrecting a removed execution, including
after restart. Installed version-one journals remain readable; the version-two
envelope is written when cleanup first needs fingerprint retention. There is no
silent eviction: at most 8,192 fingerprints are retained. If this bounded safety
record fills or storage fails, cleanup refuses without removing records. It is not
an unlimited archive, a way to reset uncertain actions, or deletion of saved routines.

`prepare improvement proposal for <scenario>` now saves the original failed/unclear
owner report, source build and report/preparation times as an immutable local brief.
Repeated preparation from identical evidence returns the existing brief. At most
16 briefs are retained, without silently replacing earlier evidence. Review them
alongside the existing recurrence suggestions with `review improvement proposals`;
`review improvement proposal for <scenario>` displays the latest brief for that
scenario. Newer reports and newer builds are labeled as superseding evidence or
requiring retesting; a passed report never rewrites an earlier failure as verified.

`dismiss improvement proposal for <scenario>` marks the latest brief dismissed while
retaining its source. `clear improvement proposals` explicitly deletes briefs while
preserving evaluation reports, baseline and recording preference. `clear improvement
evaluation` resets all evaluation data, including briefs, and turns recording off.
Installed version-one evaluation reports migrate without changing opt-in or baseline.
No private free text, conversation, provider result or screenshot is stored in briefs.
These commands neither run tests nor prepare/apply code, send to GitHub, or deploy.
The app still has no developer write integration; updater read credentials remain
unrelated. Preparing actual patches and reviewed pull requests remains future work.

Verified evidence for build 3.5.441: all 454 JVM tests across 83 suites passed with
no failures, errors or skips; all debug ABI and instrumentation APKs assembled.
Final-source CI unit/build, gateway and theme checks passed, together with all 16
optimized Android acceptance cases. The two new Android cases exercise actual-file
cleanup and review invalidation across assistant instances, plus evidence-preserving
proposal reconstruction and dismissal. All three downloaded production-signed APKs
passed independent package/version/signer, ABI, ZIP, runtime and wake-asset checks.
The signed source matches the merged app source. Broad UI-layout smoke remains
skipped upstream; physical execution, live-provider quality and installed-data upgrade
checks remain pending under the owner's existing publication authorization. The
[follow-up pull request](https://github.com/muddigger782012/J.A.R.V.I.S.-/pull/151)
records automated evidence and publication. No measured phase percentage is claimed.

## Phase 5 and 6 follow-up: goal corrections and local self-checks

Personal build 3.5.442 delivers this follow-up through the existing personal updater
and versioned downloads. Pending focus reviews can be corrected with
`make that ten minutes` or `change goal
duration to ten minutes`. `change goal volume to twenty five percent` changes the
proposed volume for any of the three templates. Each successful correction creates
a new review key and displays the revised steps, preserving all other fields and
the frozen app target. Another assistant instance's earlier approval cannot authorize
the revision. Compare-and-replace storage prevents a delayed correction overwriting
a newer proposal. A malformed/out-of-range correction clears only its exact pending
review; a missing/expired review or an approved/running task cannot be edited. An
unavailable app requires a new plan. Corrections never dispatch, learn or execute.

Opt-in evaluation also supports `run local foundation checks`, `review local
foundation checks`, `save local check baseline`, `compare local foundation checks`
and `clear local foundation checks`. Recording must be enabled before a run. These
commands execute five bounded checks against actual local planning and workflow
code, using isolated synthetic state and counter dispatchers: correction boundaries,
exact approval, interrupted recovery, attempt limits and archived-key protection.
They do not call Android device tools, providers, saved routines or user journals.
False assertions and unexpected exceptions remain failed/errored results, and later
checks continue. No test code, patch or deployment is generated by running them.

The latest actual run and one explicitly saved baseline retain only case/outcome
enums, build, check revision and time. They are separate from owner-reported manual
samples and their baseline; local passes never replace manual reports about voice,
conversation or device behavior. Matching check revisions can be compared across
builds, retaining regressions/errors without claiming a causal code fix. An older
build/revision is labeled for retesting. Disabling recording prevents subsequent
runs, clear-local removes only the local run/baseline, and clear-evaluation resets
all evaluation data and turns recording off. Installed version-one/two evaluation
records migrate while preserving reports, baseline, briefs and consent. No checks
run automatically, and no raw conversation, exceptions or private targets are stored.

Local checks establish bounded logic behavior only. They do not establish physical
device integration, provider quality, production signing or upgrade compatibility.
Verified evidence for build 3.5.442: all 462 JVM tests across 84 suites passed
without failures, errors or skips, and every debug ABI plus instrumentation APK
assembled. Final-source CI unit/build, gateway and theme checks passed, together
with all 18 optimized Android cases. The two new Android cases verify changed-goal
approval across assistants and actual local-check isolation, persistence and baseline
comparison without real device actions or manual-report replacement. All downloaded
production-signed ABI APKs independently passed package/version/signer, native runtime,
ZIP and wake-asset verification, and the signed source matches the merged app source.
The [follow-up pull request](https://github.com/muddigger782012/J.A.R.V.I.S.-/pull/153)
records evidence and personal publication. Broad UI-layout smoke remains skipped
upstream; physical phone, live-provider and installed-data upgrade checks remain
pending under existing owner authorization. No measured phase percentage is claimed.

## Next prioritized workflows: persistent reminders and developer handoff

This follow-up starts broader Phase 5 workflows with an actual local notification
reminder. `remind me in ten minutes to check the oven` freezes a title and due time
(`remind me to check the oven in ten minutes` is also supported)
for review; `approve reminder` registers it only after that exact review. A bare
yes, restored conversation or an unseen/replaced review cannot authorize an alarm.
Reviews expire after two minutes or at the due time. Titles are inert notification
data, not executable commands. Durations are strict English/digits from one second
to 24 hours; titles are bounded to 160 characters. Untimed `remind me to ...` keeps
the existing calendar-provider flow.

The approved reminder journal lives in locked atomic no-backup storage shared by
voice and chat. Stable alarm identities make restart/boot/package-update restoration
idempotent. Notification posting has its own write-ahead checkpoint: interrupted or
ambiguous delivery cannot be silently repeated. Posted means Android readback found
the notification, not that it was read or the task completed. A bounded readback
accounts for Android's asynchronous enqueueing. Missing notification permission or
a blocked channel gives actionable notification-settings/retry guidance. No exact
alarm access is needed; delivery is approximate and Android power saving may delay
it. More-than-one-day-overdue reminders are marked missed instead of posted late.

Controls: `review reminders`, `review reminder plan`, `cancel reminder plan`,
`cancel reminder 1`, `retry reminder 1`, `reminder 1 was shown`, and `clear completed
reminder history`, using the listed reminder number. Cancellation is stored before
requesting Android alarm removal, so a late callback is suppressed even if removal
fails. Only known blocked/unconfirmed registrations can retry; an owner report can
resolve uncertain delivery as user-confirmed, separately from device verification.
At most 100 records are retained. Explicit cleanup removes only posted, user-confirmed,
cancelled or missed records; active/blocked/uncertain evidence remains. Numbers are
not reused, cleared IDs cannot be revived by old broadcasts, and no entry is silently
evicted. The receiver is non-exported; all titles/authorization come from private
storage. Beta disables the receiver and omits boot permission. Developer behavior,
personal provider/model controls and selected visual themes remain separate.

Phase 6 adds `share improvement proposal for routine recovery` (or another known
scenario). It requires an existing active saved brief and constructs a text handoff
with the immutable original report, superseding-evidence labels, synthetic regression
requirements and a separately labeled local-check snapshot. It requests Android's
share chooser without preselecting a recipient. The user reviews and completes any
transfer there; the command never claims a message, issue, PR or deployment succeeded.
Missing/dismissed briefs and malformed share controls cause no activity request.
No transcript, screenshot, private target or provider credential is included, and
sharing does not mutate evaluation results or replace the original evidence. Actual
in-app patch generation/GitHub write integration remains future work.

Automated acceptance covers private-file approval/cancellation/recovery, duplicate
callbacks, expired/stale reviews, blocked permission/delivery, retry bounds, write
failures before registration and after posting, concurrent approval, capacity and
old-broadcast rejection. Android acceptance separately exercises real AlarmManager
registration/cancellation and NotificationManager post/readback with synthetic state
and a controlled clock; it cancels all test alarms/notifications. It does not claim
that a real alarm callback, reboot, doze timing or delivery on the personal phone has
been field-tested. A chooser-construction test intercepts activity dispatch and
performs no external transfer. Final-source CI/signing/publication evidence belongs
in this follow-up's pull request. Phone, live-provider and installed-data acceptance
remain pending. Contact lookup with confirmation and research-and-summary workflows
remain the next Phase 5 priorities.

## Delivered behavior and acceptance evidence

| Phase | This increment | Automated evidence | Still required |
| --- | --- | --- | --- |
| 1: core reliability | Preserves wake thresholds, speech profiles, contact matching, foreground services and themes. Reports rejected Accessibility actions as failures and Clock launches as requests. | Existing voice/contact tests; new Accessibility result tests; existing voice emulator job remains enabled. | Controlled wake/deep-voice/false-activation measurements, decode-stall and lifecycle recovery, physical-device contact and call checks. |
| 2: conversation | Explicit interpretation state replaces response-text matching. Questions mentioning commands stay conversational. Personal solution-first instructions apply in both persona modes. One configured gateway alternative can answer after a failed Gemini interpretation. | Intent, local query, fallback, personality and provider-routing tests; Android service acceptance cases. | Live provider behavior, naturalness and three-turn voice/text evaluation on the personal phone. Prompt contract tests do not prove model behavior. |
| 3: memory/offline | A bounded local dialogue window survives service/process reconstruction. Latest corrections follow older dialogue. Exact review/reset controls; confirmed device memory and validated command learning remain separate. Local arithmetic, date/time, commands and approved routines remain available offline. | Reconstruction, expiry, malformed data, deletion, write-failure and command-control tests; actual Android storage acceptance cases. | Physical restart and foreground/voice-process smoke tests. No offline generative model or arbitrary fact-learning is claimed. |
| 4: device integration | Selects implemented Standard fallbacks before an unavailable/unsupported privileged route is attempted. Maintains risk confirmations. Does not repeat uncertain adapter effects. Android rejecting an Accessibility action cannot become success. | Broker tests with injected adapters exercise actual selection, disabled fallback, denied policy, unsupported actions, failures and exceptions. | Standard, Accessibility, Shizuku and Dhizuku permission/device matrix. An advertised capability is not proof of permission or a working service. |
| 5: execution | Approved routines and reviewed focus/music/navigation goals share durable checkpoints and a voice/chat dispatch lease, bounded resume, cancellation, duplicate prevention and honest outcome types. Pending duration/volume corrections require a new exact review. Explicit reviewed cleanup retains latest/unresolved status and invocation fingerprints. | Engine/coordinator/planner tests, independent-JVM lease/death checks, bounded/migrated history tests and actual Android service/storage cases. | Physical workflow execution and broader goal decomposition. |
| 6: improvement | Read-only recurrence proposals plus opt-in manual synthetic evaluation, matched baselines, isolated on-demand logic checks with separate results/baselines, and persistent evidence-linked developer briefs with review/dismissal controls. | Deterministic tests cover recurrence, uncertainty, opt-in/reset, migration, bounded reports/briefs and honest comparison; Android reconstruction and proposal acceptance. | Live behavioral evaluation, broader reviewed feedback coverage and actual developer write integration for patches/PRs. |

The engine and pure policy tests establish their bounded contracts. Fake/injected
adapters do not prove that Android or an AI provider performed an action. Android
instrumentation is a separate check from JVM compilation and a real phone is a
separate check from the emulator.

## Local controls and data boundaries

Personal-app-only controls:

- `show conversation memory` / `review conversation memory`
- `clear conversation memory` / `reset conversation memory`
- `routine execution status`
- `resume routine execution`
- `cancel routine execution`
- `the routine worked` / `the routine failed` for an explicitly checked current routine result; reports remain user evidence, not device verification or installation proof
- `improvement status`
- `review improvement proposals`
- `review task history` / `clear reviewed task history`

These controls cannot be taught as command aliases or saved routine names. They
run locally and do not authorize unrelated pending actions. Routine status and
control refer to the current tracked execution on this device, including after
service reconstruction. Restoring status does not authorize or start pending steps.

Conversation memory retains at most 30 exchanges, 2,000 characters per side and
24,000 characters total, with seven-day expiry. It uses atomic writes and a file
lock across app processes, and lives outside Android backup. It does not import
the visible chat archive. Clearing context leaves existing preferences, validated
commands and visible chat history separate. Restored dialogue is untrusted context,
never an executable command, saved fact, contact selection or pending approval.
When a configured online provider is used, the supplied recent context is part of
that request, as it already was for in-memory conversation.

Personal build 3.5.434 used process-local routine checkpoints. Build 3.5.435 uses
the durable personal adapter described above; the library's default remains local
for callers that do not explicitly provide coordinated durable storage. No background
execution, generic autonomous planner or unattended consequential action is added.
An uncertain launch cannot be resumed automatically. Cancellation stops subsequent
steps and does not undo effects already requested.

Automatic recurrence review reads the existing bounded result journal only. The
separate opt-in manual synthetic evaluation above adds no automatic conversation
collector, telemetry, external submission, automatic code edit or deployment.
User reports remain distinct from device readback. Timestamp/target-based attempt
deduplication is conservative; independent retries with different times cannot
always be identified. Counts are recorded evidence, not measured reliability or a
proven root cause. Proposals exclude raw transcript/detail fields and use synthetic
regression scenarios. Existing result-history deletion resets recurrence evidence; evaluation has its own
explicit reset and does not erase the result journal.

## Product and upgrade preservation

Recent dialogue persistence, tracked routine dispatch, improvement controls,
solution-first provider instructions and alternative provider routing are enabled
for the personal package `com.assistant.core`. The existing shared safety fixes also
apply to shared command/result code. No Beta subscription rules, cost-routing
restrictions, provider keys, selected models, package identities, preference
namespaces, appearance resources or Developer sources are changed. The gateway's
new behavior-profile field defaults off for older/other clients.

Personal build 3.5.442 (versionCode 442) replaces the previously distributed 3.5.441
APK at the existing updater path, with the same distributed production signer.
Package identity and settings/data namespaces remain unchanged.
The stable build workflow still publishes both products on main. Feature-branch
manual runs now produce only personal signed build artifacts: both publication
steps and Beta compilation/upload require the main branch. This permits a signed
personal phone-test candidate without replacing the updater or Beta release.
A debug APK is build evidence, not a same-signer personal update candidate.

## Acceptance checklist and remaining physical follow-up

1. Run the complete JVM suite, debug build and instrumentation compilation, gateway
   suite, APK archive checks and theme contract. Record actual results below.
2. Pass the existing CI unit/build, gateway, theme and optimized voice emulator jobs
   on the final source. The voice runner requires all 21 voice/settings and isolated
   foundation service/storage acceptance tests. Broad visual-layout
   smoke remains disabled upstream; do not count it as a pass.
3. On a disposable emulator, verify exact commands, no unintended actions for
   command questions, storage reconstruction/deletion and unchanged local controls.
4. On the personal phone, test a three-turn goal conversation with a correction,
   restart continuity, offline arithmetic/commands, missing permissions, failed
   adapter alternatives and three approved multistep routines. Verify requested
   versus completed action language and cancellation. Record device/Android/build,
   steps, expected result and observed result for every case.
5. Measure wake detection on at least 20 controlled ordinary/deep-voice utterances
   per condition, aiming for at least 95% in that controlled sample. Separately
   record false activations and listening time under a reproducible negative-audio
   condition. Do not infer field reliability from the small controlled sample.
6. Produce a versioned production-signed personal APK through the existing signing
   path with production wake/voice assets. Verify package, version, supported ABI,
   APK integrity and exact installed signer compatibility.
7. Exercise an in-place update from the installed personal build. Confirm retained
   settings, keys, manual model selection, appearance, chat and learned data.
   Record these checks separately from automated verification. The owner has
   explicitly authorized personal publication with physical acceptance pending.

## Verification record for the initial foundation increment

- JVM suite: 410 tests across 77 suites passed locally, with no failures, errors or skipped tests.
- Debug assemblies: all three supported ABI APKs and the Android test APK built successfully.
- APK archive Python checks: six passed locally.
- UI theme contract: passed locally; no visual source/resource changes.
- Gateway tests: 16 passed locally, including the personal-only behavior-profile contract.
- Final-source CI and emulator acceptance: recorded in the pull request's checks; do not infer success from compilation.
- Full production-signed personal APK and all supported ABIs: verified against the distributed signer, package, version, archive and wake assets.
- Personal updater delivery and versioned APK publication: authorized by the owner after automated checks; recorded for build 3.5.434.
- Physical-device acceptance, live provider behavior, controlled wake measurements and actual in-place signed upgrade: pending.

No verified per-phase percentage or release readiness is claimed while these
acceptance gates remain open. Track the next smallest unmet contract in each phase
in parallel, starting with device evidence, broader goal planning and supervised
improvement proposals that can prepare reviewed developer changes.
