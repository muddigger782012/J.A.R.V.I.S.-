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

## Persistent reminders and reviewed developer handoff

This follow-up starts broader Phase 5 workflows with an actual local notification
reminder. `remind me in ten minutes to check the oven` freezes a title and due time
(`remind me to check the oven in ten minutes` is also supported)
and directly authorizes that reversible local notification. `prepare a reminder in
ten minutes to check the oven` instead presents a review, followed by exact
`approve reminder`. A bare yes, restored conversation or an unseen/replaced review
cannot authorize a prepared alarm.
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
performs no external transfer.

Final automated evidence for personal build 3.5.451: all 478 JVM tests across 85
suites passed with no failures, errors or skips; debug APKs for all three ABIs,
instrumentation compilation, theme contract and personal/Beta merged-manifest
separation passed. Final-source CI unit/build, gateway, theme and optimized Android
acceptance passed, with `OK (21 tests)` in the actual emulator result. Broad
visual-layout smoke is skipped upstream. All three production APKs independently
passed package/version/ABI, distributed-signer compatibility, archive integrity,
wake runtime/model and private enabled reminder-receiver checks. The build and
publication provenance is retained in the pull request and signed-artifact manifest.
Phone, actual alarm callback/reboot/doze timing, live-provider and installed-data
acceptance remain pending. The follow-up below extends the next Phase 5 priorities:
contact lookup with confirmation and research-and-summary workflows.

## Contact call review and saved research briefs

Personal Jarvis now supports `find contact Alex and confirm call`, `find contact
Alex`, `look up contact Alex` and `prepare a call to Alex`. A full phone number can
be supplied without Contacts permission. Exact normalized names take precedence;
distinct phone choices are listed instead of choosing an arbitrary recipient.
Use `choose contact 2`, `review contact call`, `approve contact call`,
`cancel contact call` and `contact call status`. A bare yes only repeats a review.
Reviews expire after two minutes, a topic change discards the pending review,
and a reconstructed service must present the current recipient again before
approval. The selected contact row/name/number is revalidated immediately before
dispatch. Without Phone permission, approval requests the ordinary dialer instead
of placing a call. Missing Contacts access offers the full-number alternative.

The private no-backup checkpoint consumes authorization before requesting Android.
Voice/chat approval races dispatch at most once. Failed storage before dispatch
causes no request; interrupted or unsaved outcomes remain uncertain and are never
automatically retried. A failed new recipient validation retains earlier uncertain
request evidence. Android accepting an intent is reported as a requested call or
dialer, never a connected/completed call. Existing direct call/dial/message commands
remain available; numbers are recognized only as complete phone strings, rather
than extracting digits from contact names. Contact review details are not added to
provider dialogue memory. Only the pending review and latest SDK-request result
are retained by this workflow; it does not create a call log or record calls.

`research solar panels and summarize` (also `research and summarize solar panels`)
performs an explicit online lookup using the existing configured grounded-search
and retrieval fallbacks. Selected provider/model settings and optional search keys
are preserved. A bounded brief extracts up to five source excerpts with linked
originals and retrieval time. It does not claim full-page access, independent fact
verification, or a generative synthesis. Returned source text is presentation data;
it never authorizes a tool or enters provider dialogue memory. Structured search
answers are now converted to readable speech before TTS instead of reading JSON.

Use `review research brief` or `summarize that research` offline to read the last
saved brief, `research status`, `cancel research`, `retry research` for an explicit
new attempt, and `clear research brief` after cancelling an active request. The
query and completion checkpoint are private, bounded and shared by voice/chat.
Only one latest brief is retained. Failure leaves an older brief intact. Duplicate
workers cannot start the same ticket twice; cancelled/superseded late results
cannot replace the saved brief. Restart never silently repeats a network request;
an unfinished request is explicitly described as running or interrupted. Cancelling
prevents saving late results and does not claim to abort an in-flight HTTP request.

Phase 6 adds opt-in manual evaluation scenarios for contact-call review and research
briefs, plus narrow synthetic contact-selection and search-fallback logic checks.
Local-check revision 2 preserves revision 1 results and baselines, labels them stale
and requires retesting before a comparison. These checks do not access contacts,
place calls, contact search providers or replace manual phone/provider evidence.
Owner feedback and developer brief sharing remain explicit and supervised.

JVM coverage includes stale/changed recipients, denial/dialer fallback, cancellation,
duplicate approval, corrupt storage, actual filesystem write failures before/after
dispatch, source validation, provider fallback, duplicate workers, restart and late
cancelled/superseded research. New Android cases intercept phone intents without
launching a Phone app, use synthetic retrieval for service reconstruction and
cancellation, and verify no private review content enters provider dialogue memory.
The optimized emulator gate now requires all 24 acceptance tests. The research
case explicitly asks about a new update so the concurrent host-state connectivity
correction cannot swallow a research request as an update-status question. Final
combined-source CI, signing and publication evidence is retained in the workflow
implementation and delivery-validation pull requests.
Final evidence for personal build 3.5.473: all 502 JVM tests across 87 suites
passed with no failures, errors or skips; all debug ABI APKs and instrumentation
compiled. Final-source CI unit/build, gateway, theme and optimized emulator
acceptance passed, with `OK (24 tests)` and all three contact/research cases in the
actual log. All three production APKs independently passed package/version/ABI,
distributed-signer compatibility, archive integrity and wake runtime/model checks
against the freshly downloaded 3.5.472 updater. The verified source includes the
latest compact utility menus, HUD refinements and connectivity diagnostics.
The signed-artifact manifest retains provenance; a normal fast-forward publication
of the locally prepared merge prevents concurrent main changes from being silently
included after verification. Broad visual-layout smoke remains skipped upstream.
Actual personal contacts, physical call/permission behavior, live search/model
quality, real phone voice interaction and installed-data upgrade remain pending.

## Optional workflow diagnostics and evidence-linked developer briefs

Contact-call, research and reminder checkpoints now feed a separate, explicitly
opt-in local diagnostic window. `enable workflow diagnostics` enables only future
observations; manual improvement evaluation consent does not enable this collection.
`review workflow diagnostics` shows the setting and categorical observations.
`disable workflow diagnostics` stops recording and retains existing evidence;
`clear workflow diagnostics` removes observations and briefs and turns recording
off. Neither command deletes reminder records, the saved research brief,
conversation memory, preferences or manual evaluation results. Entering diagnostic
controls discards unrelated pending action approvals, including contact reviews.

The no-backup diagnostic document contains only fixed enum categories, random
workflow references, build and time. It never receives contact names/numbers,
research queries/results, reminder titles, exception messages, transcripts or keys.
At most 200 observations are retained. Repeated callbacks, restoration and retries
of the same workflow/category count once within that window. Disabled or corrupt
diagnostic storage cannot change an action, outcome or workflow checkpoint.
There is no automatic submission, command learning, permission grant or code change.

`review workflow improvements` groups repeated problems by category and build;
two distinct workflow references are required. Different categories/builds cannot
be combined to manufacture recurrence. A requested call, saved excerpt, registered
alarm or posted notification retains its exact verification boundary; none proves
the original objective completed or a recurring cause was fixed. Missing access,
configuration and unavailable sources are investigation leads, not proven defects
or reliability measurements. Cancellation and supersession do not become retrieval
failures; late cancelled research produces no saved-brief diagnostic.

`prepare workflow improvement for research`, `contact calls` or `reminders` freezes
the latest recurrent category for the current build into a developer brief, including
the occurrence count, up to 20 categorical evidence links, relevant modules,
specific investigation steps and synthetic regression expectations. Older-build
recurrence alone cannot create a current-build proposal. At most 16 immutable
briefs are retained; full storage requires explicit cleanup rather than silent
replacement. New observations and window rollover do not rewrite prepared evidence.
The improvement review lists every retained brief with an archive number; `review
workflow improvement 1` with a listed number can inspect earlier or dismissed briefs.
`review workflow improvement for research` and the corresponding contact/reminder
commands inspect the saved brief; `dismiss workflow improvement for research`
retains its evidence and prevents sharing that dismissed brief. `clear workflow
improvements` explicitly removes briefs while keeping observations and consent.

`share workflow improvement for research` requests Android's destination chooser
for the latest saved brief when active. It selects no recipient and verifies no submission,
issue, pull request or deployment. The brief asks the reviewer to reproduce with
synthetic fixtures, verify regression tests before/after a focused fix and retain
normal review, CI, signing and update verification. Exact management controls,
including malformed trailing instructions, stay local before search, AI and learned
command routing, invalidate unrelated approvals and do not enter provider dialogue.
The existing `review improvement proposals` also exposes this optional review.

JVM tests cover separate consent, reconstruction/reset, duplicate attempts, build
separation, immutable evidence after window rollover, cancellation/late results,
corrupt storage and throwing diagnostic observers. A synthetic integration case
exercises all three actual workflow journals and asserts private targets are absent.
New Android service cases verify separate consent, repeated synthetic retrieval
failures, reconstructed proposal review, intercepted chooser contents, disable/reset,
malformed asynchronous controls and invalidation of pending call approvals. The
optimized emulator gate now requires all 26 acceptance cases. Physical phone,
live provider, alarm timing and installed-data upgrade acceptance remain pending.

Final automated evidence for personal build 3.5.475: all 511 JVM tests across
88 suites passed locally with no failures, errors or skips; all debug ABI APKs,
instrumentation and the Beta manifest compiled. Gateway, archive and theme checks
passed. Final-source CI unit/build, gateway, theme and optimized emulator acceptance
passed; the actual log records `OK (26 tests)` and both new diagnostic service cases.
All three production APKs passed package/version/ABI, signer compatibility against
the freshly downloaded 3.5.473 updater, archive integrity and wake runtime/model
checks. The source retains the delivered compact utility menus, HUD, connectivity
diagnostics, contact review, saved research and reminders. The reviewed signed
manifest records exact artifact provenance. Publication uses a normal fast-forward
of the locally prepared merge plus updater, preventing untested concurrent source
from being silently included. Broad visual layout remains skipped upstream;
physical calls, live providers, alarm/boot/doze timing and installed-data upgrade
remain pending. No measured phase completion percentage is claimed.

## Host-bound task composition and staged drive preparation

The personal assistant can now prepare one to three explicit steps rather than
requiring a fixed focus/music/navigation template. For example:

- `plan a task to set media volume to twenty percent then open my music app then open my maps app`
- `prepare a task to open Test Music then start a timer for ten minutes`
- `plan a task to open Test Maps`
- `prepare my drive`, or `help me get ready to drive with music and Maps at thirty percent volume`

Each clause must bind to an existing local handler before a plan is saved. Exact
installed app labels must resolve uniquely; saved browser/music/maps preferences
are resolved once and frozen as package/label pairs. All apps must be launchable
before the first effect. Duplicate packages are rejected even if their labels differ.
One optional exact media-volume step comes first, any timer comes last, and timers
retain the one-second to 24-hour bound. Unknown clauses, extra steps, calls, messages,
updater actions, shell/code and unbound package arguments cannot become partial
executable plans. The existing saved/AI routine allowlist remains unchanged;
multi-app composition is available only through host-bound goal definitions and
the checkpointed executor. This is bounded composition, not a generic AI planner.

Planning, a bare yes, status, correction and reconstruction dispatch nothing.
`approve goal plan` must refer to the exact current review; a newly constructed
assistant first presents that review again. Existing `change goal volume to ...`
and `make that ...` corrections update only an existing volume/timer field and
generate a fresh approval key. A missing field requires a new plan. Malformed
management controls stay local before search, AI and learned aliases, and discard
the earlier pending review.
Bare affirmative replies are bound to a pending goal before text-based follow-up
offers are considered. An app label containing network-settings offer wording
cannot authorize an unrelated Android settings launch.
Inactive local goal replies are also excluded from text-based manual/network offers.

App and timer requests pause independently. After checking the current requested
step, use `the goal step worked` or `the goal step failed`; the earlier `the goal
worked/failed` forms remain accepted for compatibility. A report applies only to
the current step and never dispatches pending work. `resume goal plan` explicitly
continues eligible pending steps without repeating earlier steps. Drive preparation
opens the chosen music app first; select music there, return to Jarvis, report the
checked result and resume to request Maps. Set the destination in Maps. Jarvis does
not claim playback, navigation, timer completion or the whole objective from a
launcher intent. User reports remain separate from device readback.

Cancellation stops subsequent steps and cannot undo requested effects. A failed
reported result, interrupted middle app or missing outcome checkpoint stays uncertain
and cannot be automatically repeated. Restart/status do not run anything. Every
target remains frozen; changes/removals or unavailable apps stop continuation rather
than substituting an unseen target. Existing goal definitions, focus-duration drafts,
routine journals, settings, provider/model controls and themes remain compatible.

Manual improvement evaluation gains `composed goal review` with synthetic fixtures
and reviewed developer handoff. Local foundation checks revision 3 runs a staged
multi-app fixture with counters and isolated state, not Android apps or provider
requests. Installed revision 1 and 2 reports/baselines remain readable, preserve
manual feedback and require retesting before a matched revision comparison. This
adds no goal telemetry or broader automatic diagnostic consent.

New JVM cases cover full-clause validation, exact/frozen app binding, duplicate
packages, custom corrections, old-state reconstruction, per-step outcomes, loss of
a later app, changed approval, middle-step interruption, cancellation, failed
outcome persistence and protection of the original routine allowlist. New Android
cases use isolated storage and intercepted real launcher intents for two disposable
emulator apps; no external app is actually launched. They verify service
reconstruction, pauses/resume, current-step reporting, cancellation, drive corrections,
malformed asynchronous controls, manual evaluation and isolated checks. The optimized
emulator gate now requires all 28 cases. Real-phone voice, app switching, playback,
navigation, permission and in-place upgrade acceptance remain separate work.

Final evidence for personal build 3.5.478: all 525 JVM tests across 90 suites
passed locally with no failures, errors or skips. Debug ABI APKs, instrumentation,
the Beta manifest, gateway/archive tests and the theme contract passed. Final
required CI unit/build, gateway, theme and optimized Android acceptance passed;
the actual log records `OK (28 tests)` including both staged-goal service cases.
An instrumentation-only API retention failure was corrected in the optimized test
host; a local optimized build and actual DEX API inspection also passed. Those
host-only fixes do not change production app inputs: the signed candidate's source
matches the tested production tree exactly. All three production APKs passed
package/version/ABI, signer compatibility against the freshly downloaded 3.5.475
updater, archive integrity and wake runtime/model checks. The signed manifest
retains exact artifact provenance. Publication uses a normal fast-forward of the
verified merge and updater. Current menus, HUD, themes, providers and prior workflows
are preserved. Broad visual layout remains skipped upstream; real-phone voice/app
switching, playback/navigation, permissions and installed-data upgrade remain pending.
No measured phase maturity percentage is claimed.

## Phase 5 and 6 follow-up: research reminders with frozen sources

`remind me about this research in ten minutes` and `remind me to review this
research in ten minutes` now create an explicitly requested local reminder with
a private copy of the saved source-linked brief. `remind me in ten minutes to
review this research` works too. No second lookup or cloud model is required.
The attachment is taken from the successfully saved report, never the latest
attempted query: failed, cancelled or unfinished newer research cannot relabel an
older brief. Notification delivery remains approximate and is not proof of reading
or completing the follow-up.

`prepare a research reminder in ten minutes` keeps the existing review-first path.
Its review displays the full research query, retrieval date and source count;
`approve reminder` approves only that exact frozen reminder. A bare confirmation
redisplays the review, including through asynchronous chat/voice routing. A new
service instance or changed attachment must present its current review before
approval. A fully specified direct `remind me` request retains the existing
authorization behavior for reversible local notifications.

`review reminders` lists each attached brief's command, such as `review reminder
research 1`. That command reopens its original source cards offline even after
the main research brief is replaced or cleared. Expanded reminder notifications
include the same command. The snapshot is data only: neither source excerpts nor
queries become commands, learned aliases or provider dialogue context. Reminder
management replies, including ordinary reminders, now stay outside that provider
context. Malformed follow-up and source-review controls stay local before generic
manual searches or AI interpretation.

The optional attachment extends the existing reminder journal without changing
legacy records, identifiers, due times, notification channels, approval expiry or
interrupted-delivery protections. It uses private no-backup atomic storage with
the existing bounded document size. Storage exhaustion refuses the new plan
before alarm dispatch and preserves the prior journal; there is no silent history
eviction. Clearing completed reminder history also removes those records' saved
research copies; clearing the main research brief does not remove them. Cancellation
and uncertain-delivery rules continue to prevent automatic duplicate notifications.

Manual improvement evaluation adds `research follow-up` as a fixed synthetic
scenario for feedback and frozen developer proposals. It remains off by default
and does not enable categorical workflow diagnostics. Existing opted-in reminder
diagnostics receive only their usual typed checkpoints, never attached queries,
excerpts or URLs. No code submission or deployment is automated by this feature.

Acceptance covers exact snapshot binding, old-brief/new-query failures, replacement
and clearing, reconstruction, competing approvals, permission needs, uncertain
delivery, legacy records, corrupt and full storage, literal reminder titles and
reserved malformed controls. Two additional Android cases exercise actual isolated
service storage, async routing, real Android alarm registration/cancellation and
the separately consented evaluation path. The optimized emulator gate now requires
30 cases. Final-source evidence for personal build 3.5.479: all 537 JVM tests
across 91 suites passed with no failures, errors or skips. Debug ABI APKs,
instrumentation and the Beta manifest built locally; gateway/archive tests and
the theme contract passed. Required final-source CI unit/build, gateway, theme
and optimized Android acceptance passed. The actual emulator log records
`OK (30 tests)` including both new research follow-up service cases. All three
production APKs passed completed-build provenance, package/version/ABI, production
signer compatibility against the freshly downloaded 3.5.478 updater, archive
integrity and wake runtime/model checks. The signed manifest records exact
artifact provenance. Main remained the tested base before the normal fast-forward
merge and updater publication; production sources match the signed/tested feature
exactly. Broad visual-layout remains skipped upstream. Physical-phone voice,
notification usability, live-provider quality and installed-data upgrade remain
outstanding. No measured maturity percentage is claimed.

## Phase 6 follow-up: saved proposal evidence and archive review

Improvement proposals now retain the exact matching owner-report baseline and the
saved local check run/baseline available when prepared. Clearing or rerunning
current checks cannot erase or replace the evidence sent with an older proposal.
The original defect is covered by a regression that failed before this change:
after clearing current checks, a retained proposal previously lost those executed
checks from its handoff. Missing checks stay explicitly unknown; tests run after
preparation are never attached retroactively.

`refresh improvement proposal for routine recovery` captures changed evidence in
a separate saved brief using the current checked failed/unclear scenario report.
It does not run tests, change code or overwrite the earlier brief. Repeated
preparation/refresh of the same source and evidence reuses the existing brief.
The bounded archive refuses additional entries when full, without silent eviction.

`review improvement proposals` now lists stable proposal numbers. `review
improvement proposal 1`, `share improvement proposal 1` and `dismiss improvement
proposal 1` address that exact retained brief, including older builds. Spoken
forms such as `review improvement proposal number one` are accepted. Existing
scenario commands continue to select the latest brief for that scenario. Clearing
proposals preserves the next number, preventing an old number from silently
selecting a newer brief; clearing all improvement evaluation is an explicit reset.

Handoffs include frozen check results and a matched comparison only when revisions,
case sets and ordering permit it. Different-build runs and missing baselines are
identified; passing local checks do not resolve an owner-reported failure or prove
a code fix. Current report/supersession context remains clearly separate from
the frozen evidence. Sharing still requests Android's chooser without a preselected
recipient; it does not prove external submission or create a patch, PR or deployment.

State format 4 reads existing formats 1–3 without changing consent, reports,
baselines or check revisions. Legacy proposals explicitly lack captured test
evidence; existing current results are not backfilled into them. Normal writes
migrate the format, and an explicit refresh can create a separate evidenced brief.
Clearing current checks now explains that retained proposals keep their copies;
clear improvement proposals or all improvement evaluation to remove those copies.
Malformed indexed/refresh controls stay local before learning, AI or manual search.

Acceptance covers the reproduced failure, immutable snapshots, explicit refresh,
older-build review, selective dismissal, stable numbering, lazy migration, corrupt
storage, failed writes, full archives, chronology, mismatched revisions and competing
refreshes. Two additional Android cases use actual isolated service storage,
reconstruction, intercepted chooser contents and asynchronous malformed controls;
no external submission or device action is allowed. The optimized emulator gate now
requires 32 cases. Final verification and delivery evidence will be recorded after
the checks complete. Real-phone handoff usability and installed-data upgrade remain
pending; no measured phase maturity percentage is claimed.

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

Personal build 3.5.479 (versionCode 479) replaces the previously distributed 3.5.478
APK at the existing updater path, with the same distributed production signer.
Package identity and settings/data namespaces remain unchanged.
The stable build workflow still publishes both products on main. Feature-branch
manual runs now produce only personal signed build artifacts: both publication
steps and Beta compilation/upload require the main branch. This permits a signed
personal phone-test candidate without replacing the updater or Beta release.
Feature candidates use independent branch concurrency; the main shared publisher
retains its serialization lock. The older main publishers finished before this
personal updater was replaced. The dedicated personal release publisher checks
the reviewed manifest, final app-source identity and exact signed APK hashes.
The versioned personal release contains all three ABIs, an ARM64 ZIP and checksums.
A debug APK is build evidence, not a same-signer personal update candidate.

## Acceptance checklist and remaining physical follow-up

1. Run the complete JVM suite, debug build and instrumentation compilation, gateway
   suite, APK archive checks and theme contract. Record actual results below.
2. Pass the existing CI unit/build, gateway, theme and optimized voice emulator jobs
   on the final source. The voice runner requires all 30 voice/settings and isolated
   foundation service/storage acceptance tests. Broad visual-layout
   smoke remains disabled upstream; do not count it as a pass.
3. On a disposable emulator, verify exact commands, no unintended actions for
   command questions, storage reconstruction/deletion and unchanged local controls.
4. On the personal phone, test a three-turn goal conversation with a correction,
   restart continuity, offline arithmetic/commands, missing permissions, failed
   adapter alternatives and three approved multistep routines. Verify requested
   versus completed action language and cancellation. Record device/Android/build,
   steps, expected result and observed result for every case. For research follow-up,
   save a brief, request its reminder by voice, replace or clear the main brief,
   then reopen the reminder's original sources offline. Check notification text,
   permission denial, cancellation and uncertain outcomes without duplicate delivery.
   Clearing completed reminder history removes those attached copies separately
   from clearing the main research brief.
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
