# Developer release provenance automation

This increment automates signed-APK evidence, immutable recovery preservation and source-tag alignment in the existing Developer publisher. It changes no Android source, package ID, signing identity, updater URL, provider setting or user data. Production promotion still requires an explicit dispatch from the canonical Developer branch. Task branches cannot receive the publisher's signing authority.

## Evidence and compatibility

The publisher stages its current verification helpers before selecting a historical recovery checkout. Old recovery source can therefore use current verification without relying on scripts that did not exist in that old commit. Independent APK inspection reuses `job_apk_evidence.collect` and additionally checks the permanent production certificate, expected run-number version, exact file and existing source/run receipt.

The existing `Jarvis-Developer-signed-release-candidate` artifact remains exactly the APK and `development-artifact.json`. New metadata lives in a separate evidence directory. The public versioned verification JSON and checksum text never include signing material, tokens, source excerpts or model responses.

The JSON scope is **APK_PROVENANCE_ONLY**. It verifies the file/source/signature association recorded by the trusted build. It leaves test and device-installation statuses **PENDING** because an APK inspection cannot establish either result. It does not grant an app job approval, source-write authority or installation permission. Existing CI results and development-job records remain separate evidence.

The actual uploaded APK is the exact file independently inspected in the candidate directory. The helper refuses older updater versions, changed bytes/source under the same version, unexpected packages/signers, missing source commits, bad remote digests and observed source-tag drift. Upload/readback errors fail finalization without pretending the promotion succeeded.

## Recovery and source tags

Source-only recovery snapshots remain immutable. If a later APK has the same source commit but different version/bytes, the publisher preserves a separate digest-qualified snapshot instead of overwriting the older snapshot or blocking future updates. Both metadata and downloaded bytes must match the signed baseline. The owner-controlled recovery-deferral option remains explicit; no default recovery gate is weakened.

Ordinary publication only fast-forwards the mutable `developer-latest` source tag after verifying the uploaded APK and evidence. Its former target is preserved in an immutable source-tag backup. A non-fast-forward is permitted only for an explicitly selected historical recovery source already verified within canonical Developer history. Existing recovery refs are never force-updated or deleted.

The final step verifies the unauthenticated updater API and public APK bytes, then records source-tag and evidence results. It never installs anything. Publication and finalization are separate GitHub operations: if an upload or post-publication check fails, the APK may already be published. Preserve that evidence and inspect the state before retrying; no automatic rollback or invented success is allowed.

## Verification modes

- `prepare`: inspect an already built signed APK and write bounded evidence; no GitHub write.
- `preflight`: verify the current signed baseline, version and tag proposal; read-only.
- `preserve-recovery`: preserve and read back the current signed APK without modifying existing snapshots.
- `finalize`: verify the actual published APK, upload versioned evidence, preserve/align the source tag and verify public access. Canonical manual dispatch only.
- `audit-published`: read-only inspection of the existing public update; does not claim a new build or phone installation.
- `exercise-draft-upload`: explicit opt-in metadata-only integration test. It creates an unpublished temporary draft, verifies JSON/checksum uploads and removes only that confirmed draft. It uses no APK or signing key and cannot promote a production release.

Unit contracts use labelled SDK/GitHub fixtures. The dedicated provenance workflow also audits the real public APK. A manually selected draft-upload test exercises the actual GitHub upload endpoint without replacing `developer-latest`. Samsung acceptance remains a separate owner test of published versions.
