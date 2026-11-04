# Verified Developer update review

Settings keeps its existing update button and Developer confirmation dialog. The
update path now separates **check → download/verify → Install → Android confirmation**.
An update request never establishes successful installation. No credentials, source
changes, CI dispatch, release publication or silent installation occur in this flow.

The public `developer-latest` channel, fixed APK name, application ID, signing
configuration and production certificate are unchanged. Personal Jarvis and Beta
updaters are untouched. Existing settings, HUD artwork, navigation and AI provider
preferences remain intact.

## Evidence and download boundaries

`DeveloperUpdateRelease` parses bounded UTF-8 metadata using the existing strict
coding-response JSON grammar, relocated without behavior changes into a shared
utility. It rejects duplicate/escaped duplicate keys, malformed syntax, invalid
Unicode, excess depth/nodes and trailing data. Metadata must identify the fixed
prerelease channel, immutable source SHA, positive numeric release/asset IDs,
one fixed APK URL, bounded integer size and a SHA-256 digest. The declared version
must match the actual archive version during APK inspection. Release notes and
remote error bodies are never rendered or treated as instructions.

The downloader makes GETs only, sends no authorization credentials, disables
automatic redirects and accepts one explicit redirect to the current repository's
GitHub release-asset host/path. Metadata is limited to 256 KiB, APKs to 64 MiB,
with bounded streaming and stage deadlines. Each request has cancellation and
timeout cleanup. Private download filenames are unique; a cancelled earlier
request cannot overwrite or remove a later verified download.

Downloaded length and hash are checked before APK inspection. The updater reuses
`DevelopmentArtifactInspection` for cryptographic content verification, the
production certificate pin, installed-app certificate parity, package/version,
minimum Android version, CPU architecture and bounded ZIP inventory. Build/job
IDs are never fabricated to adapt a release into a CI artifact receipt.

Current release metadata is reread after download and again before an explicit
Install request. The APK signature/hash and installed version are rechecked.
Changed source, version, bytes, identity or digest blocks the request and requires
a fresh check. A concurrent completed upgrade is reported as already current;
the updater does not offer a downgrade.

## UI, lifecycle and Android approval

Only the latest update operation for an Activity can change its status or show
a dialog. Download and Install are distinct button actions. Busy actions cannot
start duplicates, while Cancel, dialog Back/outside cancellation, page navigation
and Activity destruction invalidate the operation. A late callback discards its
own file and cannot open an installer or revive a cancelled review. Backgrounded
install verification requires another explicit Install tap after returning.

Android's unknown-source authorization remains mandatory. If missing, the
existing installer helper opens Android settings. Returning does not install:
the owner must explicitly tap Install again, causing another fresh verification.
The final installer handoff uses the existing FileProvider and installer helper.
Its success message reports only that Android's installer opened, never that an
update succeeded. A handed-off cache file remains available because Android reads
the URI asynchronously; failed and unhanded cancelled downloads are discarded.

No review/approval is persisted or restored after recreation. Source patch review,
development approvals, production merge/publication and job installation tracking
remain separate. This change does not alter recovery releases or make a blocked
autonomous candidate eligible.

## Verification boundaries

Transport and Robolectric fixtures explicitly model unsigned payloads, metadata,
thread scheduling, permission state and installer handoff. They exercise real
parsing, bounds, streaming, cancellation, actual widgets and lifecycle gates, but
do not prove an installed update. Existing provider and signature tests are retained.

The separate disposable API 34 emulator job performs three native cases: distinct
visible Download/Install actions with a modeled handoff, real Cancel rejecting a
late modeled payload, and an actual public signed APK download. The read-only APK
case checks its real digest, cryptographic certificate and Android archive metadata;
it also proves the production-signed APK is rejected against the installed debug
identity. That case never requests installation or uses signing keys.

Physical Samsung update installation, owner acceptance and production publication
remain separate and are not established by unit or emulator checks. The owner
tests published versions; no debug/unsigned phone installation is requested.
