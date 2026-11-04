# Owner-approved current-release recovery snapshot

The existing Developer publisher preserves the old APK before uploading an update.
The separate **Prepare or preserve approved Developer recovery snapshot** workflow
now exposes that same preservation implementation without requiring a new build,
signing credentials, candidate APK, update publication or installation. The existing
legacy `developer-previous` release and all older snapshots remain untouched.

## Read, review, approve, verify

Push and pull-request executions have read-only permissions. They run the release
contracts and independently inspect the actual public `developer-latest` APK using
Android SDK tools. The fixed Developer package, version, production certificate,
byte count, checksum and immutable source must agree with release metadata. A
second baseline read rejects a moving release. The bounded JSON artifact records
`CURRENT_RELEASE_RECOVERY_ONLY` and either `APPROVAL_REQUIRED` or an already
byte-verified `VERIFIED` snapshot. Neither result authorizes promotion or installation.

After reviewing a **fresh** plan, an owner may manually dispatch the workflow on
`feature/jarvis-developer`, entering its exact source SHA and APK SHA-256 and
explicitly selecting `approve_snapshot`. The default is false. Only that fixed
canonical-branch job receives `contents: write`. The helper independently rejects
push/PR/task-branch execution, another repository, a mismatched checkout, historical
source overrides, missing approval, changed APK/source and noncanonical ancestry.
Inputs are environment variables rather than executable shell substitutions.

The writer performs fresh baseline and destination checks immediately before
creation. It creates at most one immutable prerelease and uploads one fixed
`Jarvis-Developer.apk` asset. An existing exact snapshot is downloaded and verified
without mutation. Existing mismatched/partial snapshots are never overwritten or
silently repaired. If the same source has an older snapshot, its newer APK uses a
checksum-qualified tag; the older bytes remain available.

The publication workflow and the approved snapshot writer share the
`developer-release-channel` concurrency group with cancellation disabled. This
serializes their writes; read-only audits do not occupy that group. External
manual edits can still race: fresh reads and final verification fail closed, but
GitHub does not provide an atomic release-and-tag transaction.

## Failure and recovery evidence

After any attempted write, a creation, upload, identity or readback failure stops
the operation. It does not retry writes, delete a partial release, replace assets,
edit source tags or clean up state. Failure evidence says `BLOCKED`; an uncertain
write records `mutationPerformed: "UNKNOWN"`, requires a fresh audit, and exits
nonzero. The primary failure remains visible if writing local evidence also fails.
An unexpected failure before the preservation helper returns also conservatively
records `UNKNOWN`; it cannot establish that no mutation occurred. If preservation
returned successfully but local receipt storage failed, the failure report retains
the actual returned mutation result.
An interrupted process may produce no JSON; a missing report is never success.

Successful verification checks release/tag identity and downloaded bytes, then
rereads selected asset identity and the current published baseline. Volatile
download counters are excluded from identity comparisons. Reports contain public
artifact provenance only, never credentials, source excerpts or signing material.

`promotionEligibility` remains `NOT_ASSESSED`, `deviceInstallation` remains
`PENDING`, and `signingCredentialsUsed` is false. Candidate isolation, exact-source
tests, higher-version signed recovery rebuild, patch review, merge, publication
and Android installation approval gates remain separate and unchanged. The
snapshot is not an installed rollback test or evidence that a repair works.

## Verification boundaries

The regression suite models GitHub/SDK responses explicitly and verifies approval,
revision drift, idempotence, stale legacy preservation, corrupt/partial snapshots,
uncertain writes and sanitized failure evidence. Hosted read-only auditing uses
real public APK bytes and SDK signature inspection. Pull-request CI never exercises
the real writer. Its first real preservation requires a separately authorized
canonical dispatch after source review/integration; do not label that write path
operationally verified solely from fixture tests.

The separate immutable-recovery discovery improvement consumes snapshots for
candidate evaluation. This writer does not change the evaluator schema, install
an APK, merge either implementation or mark an autonomous candidate ready.
