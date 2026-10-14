# Jarvis and Jarvis Beta update channels

Implementation on this branch; a successful signed release and physical-device
acceptance are still required before calling an APK ready for installation.
Product instructions were read from documentation PR #76, commit
`27253b7481d5f4d9306aceb99c7fcc57070bea37`; that PR is not yet merged into main.

## Identities and existing API behavior

| Application | Package | Channel metadata | Legacy download |
| --- | --- | --- | --- |
| Jarvis | `com.assistant.core` | `main/updates/jarvis.json` | `main/artifacts/app-debug.apk` |
| Jarvis Beta | `com.assistant.core.beta` | `main/updates/beta.json` | `beta-latest/Beta.apk` release asset |
| Historical raw Beta | `com.assistant.core.beta` | Beta channel after the bridge | `main/artifacts/beta.apk` |

Metadata paths above are relative to
`https://raw.githubusercontent.com/muddigger782012/J.A.R.V.I.S.-/`.
Release downloads are under
`https://github.com/muddigger782012/J.A.R.V.I.S.-/releases/download/`.

Both apps still share functional source. Existing Gemini, gateway, Home Assistant,
Mycroft/OpenVoiceOS, weather, voice and other configuration/routing are preserved.
`ApiKeyStore` is unchanged: the main app does not acquire a Beta credential; Beta
continues using `JARVIS_BETA_GEMINI_API_KEY` in `BETA_GEMINI_API_KEY`, with its
existing device-secured user-key override. Full Beta still bundles its verified
offline voice archive. Package IDs, preference/data namespaces and permanent
release signing are preserved.

**Existing security limitation:** a distributed Beta APK contains an extractable
provider credential. CI secret storage does not protect a value compiled into an
APK. This branch preserves that approved behavior and adds no provider credential
to main. A proxy/per-tester-key migration needs separate owner approval. Safe build
checks compare generated configuration and compiled credential presence/isolation
without printing values. They do not establish that a provider accepts the key.
If the existing Beta credential is unavailable, production Beta builds/publication
are explicitly skipped; existing Beta download locations are preserved.

## Metadata and verification

Schema version 1 is emitted from signed APK identities, not filenames:

```json
{
  "schemaVersion": 1,
  "channel": "jarvis",
  "applicationId": "com.assistant.core",
  "versionCode": 395,
  "versionName": "3.5.395",
  "sourceCommit": "<40 lowercase hexadecimal characters>",
  "artifacts": [
    {
      "abi": "arm64-v8a",
      "url": "https://github.com/muddigger782012/J.A.R.V.I.S.-/releases/download/jarvis-latest/Jarvis-arm64-v8a.apk",
      "sizeBytes": 123456,
      "sha256": "<64 lowercase hexadecimal characters>",
      "signingCertificateSha256": "<64 lowercase hexadecimal characters>",
      "minSdk": 26
    }
  ]
}
```

This is a schema illustration, not a published version or real integrity record.
Each real manifest has all three supported splits: `arm64-v8a`, `armeabi-v7a` and
`x86_64`. Beta uses channel `beta`, its package, and matching
`beta-latest/Beta-<abi>.apk` URLs. The updater selects an ABI supported by the
installed device and validates distribution URL, size, hash, package, newer
version and signing compatibility before offering Android installation. The
installed app's certificate remains the local trust anchor; a checksum alone
does not prove a publisher's identity. Metadata and APKs use HTTPS GitHub paths.

Preflight requires exact equality with the installed app's current signing
certificate set. This schema does not support certificate rotation/signing lineage
or multiple signers; retain the historical key. `PackageManager` archive metadata
and certificate inspection do not guarantee complete cryptographic/content
verification on every Android version. HTTPS metadata plus SHA-256/size bind the
downloaded bytes; Android's system installer performs the final signature and
platform checks and requires confirmation. Opening it does not prove installation.

## Signed release publication

The existing **Build Jarvis APK** workflow name and run-number versioning remain.
It can publish only from this repository's main branch. `JARVIS_UNIVERSAL_APK=true`
adds universal APKs only where explicitly requested by release/validation jobs;
normal local and existing device-test builds retain their ABI splits.

`scripts/stage_update_channels.py` verifies complete APK signatures using
`apksigner`, reads package/version/SDK/native-ABI/label information with `aapt`,
and hashes the actual files. New APKs must match the existing permanent signer:
`93a2f2031692f947440f4cfd874a166609cd2735eeda28e60904da64e8245790`.
The workflow downloads previous public original, raw-Beta and complete-Beta APKs
and checks their real signatures/version codes. Both channel manifests and legacy
aliases must increase their corresponding installed versions; rerunning an
already published version cannot silently replace it. Missing/invalid historical
downloads fail publication rather than weakening compatibility checks.

Release outputs are copied into a verified staging directory before publication.
The publisher checks its hash receipt, skips superseded source, uploads per-ABI
and universal APKs, and downloads uploaded bytes to verify them. Versioned releases
(`jarvis-v<code>`, `beta-v<code>`) retain a recovery copy; stable releases use
`jarvis-latest` and the existing `beta-latest` tags. `Jarvis.apk` and `Beta.apk`
are universal aliases. Only after release verification are matching metadata and
legacy raw APK pointers committed together through an isolated Git worktree.
The build checkout is never reset, and a fast-forward push refuses a racing main
change. Artifact/pointer-only commits do not trigger another release build.

GitHub release assets and Git metadata cannot be updated in one atomic transaction.
During an upload or failed publication, old metadata can briefly describe replaced
stable assets. New updaters fail the hash/size check and retain the installed app;
retry after the completed publication. Versioned copies aid recovery. Old updaters
lack these checks and rely on Android's package/signature/version enforcement.
If stable assets advance but the pointer commit fails, start a new current-source
workflow run with a higher version code. Re-running the same Actions run number
cannot complete publication because staging correctly rejects the already served
version. Do not weaken the monotonic-version check to recover a partial publish.

## Historical installations and the Beta bridge

Inspection on 2026-10-14 found main release `3.5.394`, complete Beta `3.5.394-beta`,
and the frozen raw Beta from run 391 (`artifacts/beta.apk`, 52,122,305 bytes).
Complete Beta is 371,748,020 bytes because it contains the voice archive. It cannot
be committed at the historical raw-Git path under GitHub's file-size limit.

`betaBridge` is a transitional build for that historical raw-Beta cohort. It uses
the same Beta package, label, permanent signer and existing Beta API injection,
plus the new Beta updater. It preserves the old cohort's downloadable voice
behavior and existing installed voice data. It excludes the full Beta bundled
archive; **full Beta's bundled configuration is unchanged**. Existing raw-Beta
users press Update to install this bridge, then press Update again to obtain the
complete Beta from its channel. No uninstall, data reset or manual file-manager
step is required when signatures and Android permissions permit the upgrade.

For workflow code `N`, original/full Beta use code `N`; the raw-Beta bridge uses
`N-1`. Staging must prove that `N-1` exceeds the previous raw-Beta code and is below
the complete Beta code. Current complete-Beta users receive full Beta directly
and are never offered the bridge through metadata. A next workflow code of 395
would yield bridge 394 above legacy 391 and full Beta 395 above installed 394;
this is an example, not evidence that those APKs were built.

Original and bridge raw aliases must include all three native ABIs and remain
below a conservative 100,000,000-byte Git-file bound. If real builds exceed it,
publication fails and the prior aliases remain. Do not claim universal legacy
support until actual output sizes and installations pass validation.

Some earlier original APKs hardcoded repository `assistant-platform` and branch
`cursor/cursor-build-pack-project-8400` before repository-fix commit `2a930710`.
Code installed on a phone cannot be repaired by changing a different repository's
URLs. Those versions require a one-time direct same-signer installation unless
the owner can restore the hardcoded distribution location. A debug-signed or
other-signer installation also cannot upgrade to this release signer; never
recommend uninstalling as an automatic workaround because that risks user data.

Android still requires user confirmation and may request install-unknown-apps
permission. Download completion, opening the installer and successful installation
are distinct states. Background services, storage limits and device policy can
affect installation; no silent installation or platform bypass is provided.

## Validation and release acceptance

Available local checks include:

```sh
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
python3 -m py_compile scripts/stage_update_channels.py scripts/publish_update_channels.py scripts/verify_beta_configuration.py
python3 scripts/check_ui_theme.py
bash gradlew --no-daemon testDebugUnitTest assembleDebug
```

PR CI additionally compiles optimized unsigned original/full-Beta/bridge variants,
checks API configuration presence/isolation using a non-provider test sentinel,
and verifies full Beta's bundled archive and its absence in original/bridge.
PR workflows receive no production signing/provider secrets and never publish.
Unsigned compilation is not evidence of an installable production update.

Release and PR compilation use the same `scripts/prepare_android_runtime.sh` to
package the existing Sherpa runtime and complete production KWS model archive,
including generated Hey Jarvis tokens. PR CI runs the existing native decoder
smoke check, then `scripts/verify_legacy_apk_budget.py` verifies every prepared KWS
asset against the universal original/bridge APKs and confirms all three native
ABIs and existing Sherpa libraries are present. It records their measured byte
sizes in `updater-legacy-apk-budget` and requires each unsigned APK plus 64,000
bytes of signing headroom to remain below 100,000,000 bytes. Signed production
staging still checks final signed file sizes; headroom is a conservative PR gate,
not evidence of a production signature or historical-device upgrade.

The separate updater emulator workflow builds version 108/109 debug-signed main
and Beta fixtures and checks package/signature/integrity rejection and persisted
settings/device-secured keys across `adb install -r`. Those fixtures are generated
from this branch; they are not representative historical production APKs. They
do not establish physical-phone Update-button behavior or cloud API success.

Before release, verify signed historical-main → new-main, raw-Beta → bridge →
full-Beta, and full-Beta → new-full-Beta on representative devices/ABIs. Confirm
settings, conversations, saved commands, integrations and voice data survive;
check actual API behavior, voice preparation and Android prompts. Exercise no
update available, unsupported ABI/SDK, wrong channel, wrong signer, malformed or
stale metadata, broken network, interrupted/corrupted downloads, storage failures,
permission denial/cancellation and retry. Report each result with the tested APK
codes/signers and device details. Do not label untested artifacts release-ready.
