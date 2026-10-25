# J.A.R.V.I.S. Assistant ↔ Developer integration contract (draft)

## Current implementation baseline

Personal Assistant and Developer remain independent Android packages. The Personal SelfDiagnosticsActivity currently sends explicit text/plain ACTION_SEND intents to com.jarvis.developer/.MainActivity. This is a user-initiated review draft, **not** approval to modify code. Preserve this fallback for older Developer builds.

## V1 transport

Introduce a versioned, explicit Android IPC contract only after verifying the Developer receiver and its manifest. Do not silently replace ACTION_SEND. The receiver must be explicitly addressed, non-exported where feasible, and enforce the caller's signing identity and permission at the receiving boundary. A signature-level permission alone does not authenticate apps signed by different keys. If signers differ, use a user-confirmed explicit handoff with narrow payloads instead of an unauthenticated exported service.

## Messages

- `capabilities.request` → `capabilities.response`: protocol version and supported operations, no credentials.
- `diagnostics.review.request` → `diagnostics.review.accepted`: bounded, validated finding IDs, relative paths and revision claims explicitly labeled unverified; never raw logs, secrets or imported instructions.
- `task.status.request` → `task.status.response`: opaque task ID and read-only state, subject to caller authorization.
- `repair.proposal.request` → `repair.proposal.response`: Developer verifies repository revision, scope and complete diff before requesting independent owner approval.

Every message requires a version, unique request ID, bounded payload size, explicit target, timeout and deterministic error response. Reject unknown operations, oversized payloads, replayed IDs and unsupported versions. Do not log private payloads.

## Non-negotiable approval boundaries

No incoming Assistant message may authorize source writes, GitHub pushes, merging, signing, publication, APK installation, root commands or accessibility operations. Developer must independently request explicit approval for the exact revision and diff. Scope/revision changes invalidate prior approval. Merge, release and installation require separate approvals.

## Preservation and verification

Do not change Assistant chat, voice capture, wake word, settings, HUD, Beta routing, app IDs, signing, or updater behavior in this integration increment. Keep the existing diagnostic ACTION_SEND fallback. Test missing companion, old companion, mismatched signatures, malicious caller, malformed and oversized payloads, replay, process death, cancellation, and backwards compatibility. Run Android build, lint, JVM, instrumentation, emulator and physical-device handoff tests before release. No successful build or release is claimed by this document.

## Next engineering step

Locate the Developer source/manifest on the actual development branch, identify the exported share receiver, signing relationships and approval flow, then implement and test the smallest compatible read-only capability handshake. Do not publish an APK until those tests pass.
