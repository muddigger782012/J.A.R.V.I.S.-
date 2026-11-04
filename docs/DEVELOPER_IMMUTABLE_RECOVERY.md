# Immutable recovery evidence for controlled development

The build-only candidate evaluator previously read only `developer-previous`. The publisher preserves immutable snapshots instead of overwriting older recovery releases. The evaluator now recognizes both formats while retaining the existing approval, isolation, tests and higher-version recovery requirements.

## Bounded read-only selection

Given a signed baseline already inspected with the Android SDK, try only these fixed candidates, in order:

1. `developer-recovery-<source SHA>-<APK SHA-256>` for repeated builds of the same source.
2. `developer-recovery-<source SHA>` for a source-only immutable snapshot.
3. The legacy `developer-previous` alias.

No release search, arbitrary tag, remote instruction, ref change, upload, signing or installation is introduced. Existing aliases and older snapshots remain untouched. Source-only snapshots of an older version can be skipped; a corrupt digest-qualified copy fails closed rather than silently falling back. Immutable refs must point directly to the named source commit. Legacy annotated refs remain supported within the existing depth limit.

Before verification, require the release/tag identity, published prerelease status and expected asset metadata. Inspect downloaded bytes using the existing APK parser and Android signature tools. Source, package, version, APK digest, byte size and signer must all equal the baseline. Then re-read the selected release/asset/ref identity and the latest APK to detect changes during inspection. The existing 24-request controller budget is retained.

The v1 candidate-evidence schema remains unchanged. Existing Developer releases can consume the same `backup` facts with a verified flag, regardless of which supported tag supplied them. This change grants no approval and does not make a canonical controller build into an isolated candidate. Missing current backup, wrong source/certificate/bytes, incomplete tests, non-isolated source, expired review or missing exact owner confirmation continue to block promotion.

## Read-only audit operation

`candidate_audit.py audit-recovery` inspects current backup discovery without signing or assessing promotion. Its separate report scope is `READ_ONLY_RECOVERY_DISCOVERY`, and `promotionEligibility` is always `NOT_ASSESSED`. A completed audit can report `currentBackup.status=BLOCKED`; successful inspection does not mean a backup exists or that promotion is eligible.

Optional reference source/digest arguments verify a known historical immutable snapshot with the same SDK reader. That proof is explicitly historical and cannot substitute for a backup of the current release. The dedicated read-only workflow checks the real preserved 0.1.183 APK against its previously verified source and digest, alongside the actual current lookup. It uses only Contents: read, and has no signing or release-write credentials.

Read-only refers to repository/release/source operations. Downloaded APKs and bounded JSON evidence are written to the chosen local output directory. No current release or recovery is created by this milestone. In particular, an older 0.1.183 snapshot cannot satisfy the required exact backup for published 0.1.185.

Unit tests use explicitly modeled SDK and GET transports, including drift, corrupted copies, legacy compatibility, request budgets and unchanged promotion restrictions. Android tests continue to verify the existing guided interface and approval controls. Physical-device acceptance and release publication remain separate steps.
