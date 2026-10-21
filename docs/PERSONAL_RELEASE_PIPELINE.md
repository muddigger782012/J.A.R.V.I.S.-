# Personal Jarvis release pipeline

Production signing continues to use the existing repository signing secrets. No
new key, app identity, updater endpoint or commercial configuration is introduced.
Personal versions now use `3.6.<build-run-number>`; the Android version code remains
the monotonically increasing build run number. Beta keeps its independent existing
versioning, packaging and publication.

## Automatic publication

The signed build verifies all three Personal APKs against the production signer in
the currently distributed APK. It checks package/version/ABI, archive integrity,
local runtime and wake assets, absence of Beta payloads and exclusion of test-only
Accessibility fixtures. It generates a release manifest and checksums and retains
them as the `Jarvis-personal-release-metadata` build artifact.

After a successful main build, **Publish verified personal release** automatically:

1. Checks the completed build's repository, workflow, source, version and ancestry.
2. Requires unchanged application inputs on current main.
3. Waits for successful source-equivalent unit/build, gateway, real Accessibility
   emulator and theme jobs. Missing, skipped or failed required jobs block delivery.
4. Downloads the signed APKs and independently repeats production verification.
5. Generates the manifest, deterministic ARM64 ZIP and checksums automatically.
6. Commits the manifest and newer ARM64 APK to the existing updater path using a
   normal fast-forward push. A concurrent main change fails safely; no reset or
   force push can overwrite another task.
7. Creates or resumes a draft GitHub release, checks every uploaded byte, publishes
   it, and verifies public downloads plus the actual raw updater against Git data.
8. Retains a publication receipt. Physical-device acceptance remains separately
   pending unless actually performed; CI does not claim it.

The existing main-build Personal updater write is replaced by this gated publisher.
Beta and Jarvis Developer publishing remain separate. The updater continues to use
`artifacts/app-debug.apk` with the same production signing identity and Android
installation confirmation. Settings, app data and HUD styling are unaffected.

## Retry and historical releases

A manual dispatch accepts a completed **build run** and needs no manually prepared
artifact. A source-equivalent acceptance run must still have passed. A publication
that loses a main race can be retried on current main if application inputs match.

A **version** selects an existing reviewed manifest, including Personal 3.5.496.
This permits completing a historical download after the updater has advanced, but
never downgrades or rewrites the newer updater. Equal version codes must contain
identical APK bytes. Published releases are immutable: retries verify their files
rather than clobbering them. The five download assets are three APKs, one ARM64 ZIP
and `SHA256SUMS.txt`. Curated notes come from `docs/PERSONAL_RELEASE_NOTES.md`.

The screen-control source was merged before this automation change and the legacy
main publisher distributed Personal 3.5.497 from the same application source. The
requested 3.5.496 remains a historical signed-download publication; the newer
updater is preserved.

## Validation

Run `python3 -m unittest discover -s scripts/tests -p 'test_*.py'` for release and
archive regression checks. These cover signer changes, wrong versions/ABIs,
missing wake assets, Beta/test-fixture leakage, tampering, duplicate/unsafe manifest
entries, deterministic checksums/ZIPs, downgrade prevention and required acceptance
gates. Actual Android tools must additionally reproduce the reviewed manifest from
real signed APKs. Successful workflow receipts verify upload/download and updater
publication end to end; parsing YAML or passing units alone is not delivery proof.
