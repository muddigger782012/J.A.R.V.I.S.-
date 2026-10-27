# J.A.R.V.I.S. Assistant ↔ Developer integration contract

## Installed APK preflight checks (Personal applications)

The optional `task.artifact.installed.check.request` operation requires the
authenticated Personal Assistant, original job owner/mode, exact revision and a
current verified artifact. Its body is empty: clients cannot submit passing test
claims. Developer performs bounded local reads outside the checkpoint lock,
checks package, version, signer, APK bytes and launcher availability, then re-reads
Android's package record to reject replacement during inspection. APK hashing
checks interruption/deadline between buffers. No installer or application launch
is invoked. Missing packages, changed versions, unavailable reads and deadlines
produce fixed sanitized diagnostic categories.

Only results still bound to the same job revision and artifact are saved in the
existing private artifact extension. Public status contains fixed check names,
results, actual elapsed time, Android API and observation time, never APK paths,
device identifiers, source logs or credentials. Observations become stale after
one minute, a wall-clock rollback, process restart, job change or changed artifact.
The optional extension retains the previous version-one checkpoint stage values.

The existing Developer recheck button also checks completed installations. The
Assistant command routes locally without online AI; older Developer versions must
negotiate support. Commercial peers are rejected at negotiation and execution.
Five passing package preflight checks **never pass functional device acceptance**:
`deviceVerified` remains false, and no `DEVICE_INSTALLED=PASSED` is derived. Voice,
conversation and screen behavior require separate real acceptance evidence.

## Conversational APK installation consent (Personal applications)

`task.artifact.install.review.request` prepares metadata, never an installer
session. Its two-minute, one-use ticket reuses the existing private review
checkpoint with an explicit installation purpose and the same immutable APK,
source, build, owner, mode and revision binding. Navigation tickets cannot be
used for installation, nor installation tickets for navigation. Preparation and
delivery reject an existing active installation. Only Personal Assistant and
Personal Developer negotiate these optional v1 operations; policy and admission
also reject commercial participants independently of the UI.

Assistant names the verified app, build and signing class, then holds consent
context only in memory. Only a fresh user utterance explicitly approving this
APK installation can deliver it to Developer's known activity. Generic yes is
rejected, unrelated turns discard context, and cancellation, expired offers,
mode/project changes and activity recreation cannot restore consent. Generated
AI replies never enter this approval route. An older Developer falls back to the
existing review page and its owner button.

Developer consumes installation consent only in its foreground activity, then
reuses the existing installer and rechecks actual APK bytes and compatibility.
Missing Android permission consumes this consent without creating a session;
after granting permission the owner must review and approve again. No authority
is saved in activity state or automatically resumed after interruption.
`task.artifact.install.cancel.request` invalidates this job's pending handoff and
installation tracking and attempts to abandon its owned Android session. It
cannot undo an installation Android already completed. Actual installed-byte
observations still leave device acceptance blocked. There is no generic
approval-grant, installer execution, merge, signing or publication RPC.

## Conversational APK review handoff

`task.artifact.review.request` is an optional, negotiated v1 navigation operation.
It requires the authenticated job owner, original permitted mode, exact revision
and a current verified APK. A two-minute, one-use ticket is stored in the existing
private job checkpoint, bound to that revision and APK/build fingerprint. An
identical retry returns the original unconsumed ticket; expiry, consumption,
changed evidence, cancellation or replacement cannot renew it. Normal status and
list responses never expose the ticket. Older checkpoint readers can ignore the
extension; older bridges reject the operation during capability negotiation.

Explicit local voice/text requests to review the APK Developer built request
this navigation handoff. Older bridges also use it for install wording. They do not approve an
installation. Assistant launches only Developer's known activity after checking
its identity again and rejects stale conversation delivery. Developer consumes
the ticket and focuses the exact job in its existing Tools inbox, including jobs
outside the latest-eight list. A consumed ticket acknowledges delivery; it is not
proof that the UI was seen, Android installed anything, or device acceptance ran.
Configuration restoration retains only the viewed job; it never reuses approval.

Android foreground/background launch restrictions still apply. An unavailable or
expired handoff requires a fresh explicit request. Owner APK approval, unknown-
source permission and Android installer confirmation remain separate. No source
write, signing, publication, install RPC or Android privilege is added by navigation.

## APK evidence and local installation increment

The authenticated status response optionally includes `verifiedArtifactReview` and
an artifact summary. There is no IPC installation or approval-grant operation.
Developer's existing inbox can inspect a retained workflow artifact from the exact
verified job source. The archive digest, bounded source/run/attempt manifest, APK
digest, cryptographic signature, installed family signing identity, package,
version, Android level and ABI must all verify before `SIGNED_APK` can pass.
Debug and production artifact labels remain distinct. Older artifacts without
the current provenance record are rejected rather than inferred compatible.

An explicit owner action in Developer or the purpose-bound Personal Assistant
consent flow above is required to create an Android installer
session. Android's unknown-source permission and confirmation are independent.
Session IDs and fresh nonces bind a non-exported result callback to the current
job. Cancellation invalidates later evidence and attempts to abandon the owned
session; it is not rollback of an operation Android already completed. Recovery
only observes sessions and matching installed package bytes; it never commits or
replays an installation. Interrupted confirmation may require cancelling the
old tracking and a new explicit request. `PACKAGE_PRESENT` records an installed
package observation; `DEVICE_INSTALLED` remains blocked until separate acceptance
evidence is implemented. No source scope, mode, APK receipt or installer callback
grants merge, signing, publication, root or accessibility authority.

The v1 checkpoint keeps the previous release's last-two-stage values compatible;
newer versions derive APK and installation stages from typed evidence extensions.
If an older version updates its build evidence, an unmatched artifact becomes
stale and cannot authorize installation. APK identity, version, signing class and
workflow are retained in approval/result audit entries. Explicit legacy recovery
builds whose source predates the provenance adapter retain the existing verified
release path without claiming a new job artifact receipt.

Personal job provenance uses `Jarvis-personal-job-APK`. The existing updater still
receives `Jarvis-release-APK` containing exactly its three signed APKs. Its strict
input inventory, signing, checksum and publication checks remain unchanged.

## Phase A implementation under verification

The isolated integration branch implements a shared primitive Binder protocol in `integration/src/main/kotlin`. Both apps compile the same source. Developer's exported service requires its signature permission **and** verifies the actual calling UID against exactly one known Assistant package and Android's signing records. The client checks the installed Developer identity before and after explicit binding, then negotiates the protocol, requested operation and mode on the authenticated connection. No caller identity, approval or completion evidence can be supplied in a request.

Published Personal and Developer APKs were independently inspected and have matching production signing identities. A differently signed companion is rejected. This does not authorize changing either identity or publishing these changes.

Implemented operations are capability negotiation, read-only diagnostic review admission, status/list, pause/resume/cancel and bounded retry. Revision-checked controls prevent stale cancellation from changing a later attempt. Identical request IDs and scope return the existing job; reusing an ID with changed scope is rejected. Assistant retains unacknowledged requests on disk and can retry them explicitly after process recovery. Developer uses its existing private atomic checkpoint implementation with a transaction lock, checksum and bounded audit receipts. Corruption fails closed.

Both apps expose policy-profile selection with audit records. Owner Development is rejected for commercial sender or receiver at the canonical policy, transport and admission/control boundaries. Resume/retry requires the original mode; choosing a mode is not a source-scope grant. No source writer or installer is exposed by this bridge. All five job verification stages remain PENDING or BLOCKED until later engineering integrations can supply actual independently checked evidence.

The diagnostics page prefers authenticated IPC. It offers the legacy share interface only after a missing/unsupported bridge response, verified installed Developer signing identity, and explicit user confirmation. Failed identity or policy checks never trigger sharing. Back/Close destroys the UI delivery ticket and cannot revive a stale result dialog.

Verification is recorded in the development milestone report and pull request. The conditional `developmentIntegrationTest` source set and independent test-peer APK exercise real installed-app Binder communication on disposable emulators. They are excluded from normal acceptance suites and production APKs. Full workflow execution, source execution, standing scope grants, signing/installer integration, publication and owner-device acceptance must not be inferred from review admission.

## Current implementation baseline

Personal Assistant and Developer remain independent Android packages. Before this increment, SelfDiagnosticsActivity sent only explicit text/plain ACTION_SEND intents to Developer's MainActivity. That user-initiated review draft is **not** approval to modify code; the trusted, confirmed fallback remains available for older Developer builds.

## V1 transport

Introduce a versioned, explicit Android IPC contract only after verifying the Developer receiver and its manifest. Do not silently replace ACTION_SEND. The receiver must be explicitly addressed, non-exported where feasible, and enforce the caller's signing identity and permission at the receiving boundary. A signature-level permission alone does not authenticate apps signed by different keys. If signers differ, use a user-confirmed explicit handoff with narrow payloads instead of an unauthenticated exported service.

## Messages

- `capabilities.request` → `capabilities.response`: protocol version and supported operations, no credentials.
- `diagnostics.review.request` → `diagnostics.review.accepted`: bounded, validated finding IDs, relative paths and revision claims explicitly labeled unverified; never raw logs, secrets or imported instructions.
- `task.status.request` → `task.status.response`: opaque task ID and read-only state, subject to caller authorization.
- `repair.proposal.request` → `repair.proposal.response`: Developer verifies repository revision, scope and complete diff before requesting independent owner approval.

Every message requires a version, unique request ID, bounded payload size, explicit target, timeout and deterministic error response. Reject unknown operations, oversized payloads, scope-changing replays and unsupported versions. Identical replays acknowledge the existing job without performing a second operation. Do not log private payloads.

## Non-negotiable approval boundaries

No incoming metadata message may authorize source writes, GitHub pushes, merging, signing, publication, root commands or accessibility operations. APK installation requires the exact, separate owner consent flow above and Android's own permission and confirmation; a metadata request alone never creates a session. Developer independently requests explicit approval for the exact source revision and diff. Scope/revision changes invalidate prior approval. Merge, release and installation require separate approvals.

## Preservation and verification

Do not change Assistant chat, voice capture, wake word, settings, HUD, Beta routing, app IDs, signing, or updater behavior in this integration increment. Keep the existing diagnostic ACTION_SEND fallback. Test missing companion, old companion, mismatched signatures, malicious caller, malformed and oversized payloads, replay, process death, cancellation, and backwards compatibility. Run Android build, lint, JVM, instrumentation, emulator and physical-device handoff tests before release. No successful build or release is claimed by this document.

## Next engineering step

Complete installed-app acceptance and preserved-feature regressions, then connect acknowledged review jobs to the existing source-verification and exact-diff repair workbenches with independently recorded evidence. Do not introduce source-execution operations until task scope and owner authorization are enforceable. Production merge, signing, publication and installation remain separately authorized.
