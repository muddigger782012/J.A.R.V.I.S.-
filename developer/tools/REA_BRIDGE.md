# J.A.R.V.I.S. Developer ↔ REA APK static-analysis bridge

REA upstream: https://github.com/morluto/rea (MIT; preserve its LICENSE and upstream third-party notices).
This integration **does not bundle or modify REA/JADX**, install a third-party executable
on the Android device, or run APK bytecode. It invokes REA through its documented
`rea inspect-android-package` CLI on a separately owned host.

## Supported scope and requirements

- Install a compatible released `rea-agents` package on a dedicated Windows/Linux
  x64 or macOS host with Node 22.23.4+, supported Java/JDK and REA's pinned
  headless JADX package. Follow upstream's platform and provider prerequisites:
  https://github.com/morluto/rea/blob/main/docs/android-analysis.md
- Verify the REA provider locally with `rea doctor --provider jadx --json`
  and a locally owned sample APK before connecting Developer.
- Python 3 is needed only for this isolated HTTPS bridge. The bridge accepts
  **one APK of at most 24 MiB** per analysis; no queue, installation, or
  arbitrary REA commands. The inspected ZIP is not executed.
- This is a **hosted** REA capability, not a promise that REA's full desktop
  JADX analysis engine runs natively inside the Developer APK. Users without
  a compatible REA/JADX host cannot invoke the analysis.
- **Windows compatibility:** the REA bridge is Python's standard HTTP/TLS
  service and uses a separate Node runtime on Windows, avoiding fragile
  `.cmd` and shell execution. A future native Windows Developer client can use
  exactly the same HTTPS endpoint, JSON evidence schema, APK SHA-256 binding
  and source-proposal review gates without depending on Android APIs.
- Windows CI runs `rea_bridge_test.py` separately from Android compilation.
  This verifies the wire/launcher contract, not a full Windows GUI or the
  availability of REA/JADX on a specific computer.

## Start an authenticated TLS bridge

Configure a valid certificate for the hostname that Developer will use.
For a phone outside the same host, prefer a trusted VPN, private hostname
and firewall. Only expose the bridge to trusted devices. It requires TLS
even on loopback; self-signed certificates that Android doesn't trust are
refused by the Android client.

```sh
export JARVIS_REA_TLS_CERT=/absolute/path/fullchain.pem
export JARVIS_REA_TLS_KEY=/absolute/path/privkey.pem
export JARVIS_REA_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
export JARVIS_REA_HOST=127.0.0.1
export JARVIS_REA_PORT=8765
python3 developer/tools/rea_bridge.py
```

Set `JARVIS_REA_HOST` to a permitted interface only if Developer needs
to reach a separate machine; apply a firewall rule and trusted TLS
certificate before doing so. Copy the **generated bridge token** privately
into Developer Settings, never into GitHub or an AI prompt. REA CLI path can
be configured via `JARVIS_REA_COMMAND` on the host. The API never accepts
a caller-selected executable or analysis path.

### Windows host (PowerShell)

Install Python 3, a compatible official Node.js release, the official
`rea-agents` npm package, and the JDK/JADX prerequisites described by REA.
Find the installed **scripts/rea.mjs** path. Use an explicit Node executable
and JS entry, never a `.cmd` shim:

```powershell
$env:JARVIS_REA_COMMAND = 'C:\Program Files\nodejs\node.exe'
$env:JARVIS_REA_ENTRY = 'C:\REA\node_modules\rea-agents\scripts\rea.mjs'
$env:JARVIS_REA_TLS_CERT = 'C:\certificates\rea-fullchain.pem'
$env:JARVIS_REA_TLS_KEY = 'C:\certificates\rea-key.pem'
$env:JARVIS_REA_TOKEN = 'REPLACE_WITH_A_LONG_RANDOM_SECRET_24_PLUS_CHARACTERS'
$env:JARVIS_REA_HOST = '127.0.0.1'
python developer/tools/rea_bridge.py
```

The paths are examples: set these variables to real, verified installations
and certificates on the Windows host. Never commit a real token or TLS private
key. To reach the service from the Android phone, bind a trusted LAN/VPN
interface with firewall restrictions and use a hostname whose TLS certificate
is accepted by the device.

In Developer, open **Settings → REA APK analysis bridge**, save the HTTPS
origin and bearer token (Android Keystore encrypted). Return Home, tap
**+ APK**, pick a file, and then tap **Analyze APK**. This second tap is
the explicit upload authorization. The bridge accepts only:

- `POST /v1/apk/analyze`
- `Authorization: Bearer <configured token>`
- `Content-Type: application/vnd.android.package-archive`
- `X-APK-SHA256: <64 lowercase hex characters>`
- raw selected APK bytes

The bridge preflights the APK's actual ZIP directory before invoking REA/JADX.
Invalid ZIPs, missing Android manifests, unsafe or duplicate entry names, symlinks,
encrypted members, and oversized declared expansion are rejected. This is not a
malware scan or proof of extraction completeness.

REA findings are projected into a bounded, readable summary in Developer chat:
package/version, SDK levels, class/resource counts, permission declarations,
and manifest/component coverage. Unknown data remains unknown and no
runtime-behavior or safety claim is inferred. Source proposals include this
summary inside the untrusted evidence boundary with the original evidence hashes.
Old bridge responses without a summary remain accepted.

Successful analysis returns `jarvis.rea.apk.v1` with the APK SHA-256,
REA output SHA-256, bounded evidence excerpt, truncation status, and
static-only limitations, and bounded ZIP entry count and declared uncompressed
size when available (not independently extracted). The host keeps a full evidence bundle only until
the per-job temporary directory is removed; therefore the mobile excerpt
must not be mistaken for complete decompilation or a durable full report.

## Actionable REA failure feedback

Developer now maps the bridge\u0027s fixed error codes into focused, safe chat guidance
instead of a generic connection failure. The health check and APK analysis
identify token failures, busy hosts, invalid APKs, SHA mismatch, absent
REA/JADX configuration, timeouts and invalid evidence. The Android client
never displays untrusted server `message` values, exception details or secrets.
Failures never authorize code edits or APK installation.

## Test the connection before uploading an APK

Developer Settings now offers **Test REA connection**. It sends one
authenticated `GET /v1/health` with **no APK bytes**, no file path and no
remote command supplied by the client. The server runs the fixed
`rea doctor --provider jadx --json` check with a 30-second timeout.
Returned states are:

- `BRIDGE_REACHABLE / DOCTOR_PASSED`: TLS and authentication succeeded,
  and REA/JADX doctor exited successfully; this **does not** establish that
  any particular APK will decompile.
- `BRIDGE_REACHABLE / DOCTOR_FAILED`: bridge reachable, but Node/JDK/JADX
  provider readiness failed; inspect the host's own REA installation.
- 401/429/transport failure: invalid token, busy service, TLS/network
  failure. No readiness is invented and no file is uploaded.

The health route is available to both the Android client and a future
native Windows Developer client. It never returns local host paths,
API credentials, subprocess output or analysis evidence.

## Offline APK inspection (no REA host needed)

On Developer Home chat, use **+ APK** to select a local archive, then
select **Inspect locally**. Android parses the archive manifest with
`PackageManager.getPackageArchiveInfo` to display the package ID, version,
SDK compatibility, requested permissions and declared components.

- Runs **entirely on Android**: no upload, REA server, AI provider,
  source write, APK installation, or runtime execution.
- Binds the result to the selected APK SHA-256 and refuses changed bytes.
  The Android parser requires a private temporary cache file; it is deleted
  immediately after parsing. No APK archive is retained by the inventory.
- Requires an explicit user-selected APK. **Analyze APK** remains a separate
  opt-in network upload to the authenticated REA/JADX host.
- Adds an on-device cryptographic APK signing check using the existing
  `apksig` verification engine for the device's Android SDK. A passing
  result displays SHA-256 fingerprints of the verified signing certificates.
  A failed signature check is explicitly labeled **FAILED**, never silently
  upgraded to trusted.
- Cryptographic validity does not prove who owns a certificate or whether
  its app is safe. This is not complete decompilation or dynamic behavior
  testing. No imported APK is installed or executed.

The offline Android parser is a platform adapter. The future Windows client
should implement an equivalent ZIP/binary-manifest parser for the same
inventory contract, not call Android PackageManager from Windows.

## Read-only REA class search (active chat)

After Analyze APK succeeds, the selected APK remains in volatile memory only
while Developer chat is active. Type a 2–80 character class-name substring in
the existing composer (for example, MainActivity), then tap Search classes.
Developer sends the same selected bytes and SHA-256 with an explicit
authenticated POST /v1/apk/classes/search and X-REA-CLASS-QUERY header.
The bridge validates the query and archive and executes only its fixed
search-android-classes REA command. It returns class names, a bounded count,
the REA evidence SHA-256, and static-only limitations; it does not execute
or persist the inspected APK.

Search results are available to a subsequent Prepare REA proposal as explicitly
marked untrusted evidence. They never grant write approval or bypass the
existing source fingerprint review. Ordinary chat follow-ups preserve the
completed, APK-bound evidence in this active conversation. Interrupting an
in-flight analysis discards the pending evidence. Starting a new chat, switching
projects or backgrounding/closing the Activity clears the evidence and zeroes
selected bytes. A new session requires selecting the APK again.
The companion Windows client can
use the same portable HTTPS contract.

## Inspect a specific REA class

Following Search classes, copy one exact displayed class name into the composer
and select Inspect class. Developer submits the same selected APK and SHA-256
through authenticated POST /v1/apk/classes/inspect with X-REA-CLASS-NAME.
The host runs only the fixed REA inspect-android-class command, then returns a
bounded listing of reported method signatures, overload indices, fields and
inner-class counts. No application bytecode or native code is executed.

Class inspection is allowed from an exact class name in the preceding search
results for that APK. Chat evidence and source-proposal context preserve both
APK and inspection-output hashes. Class members are untrusted static facts;
they cannot grant approval, commit files or install software.

## Method-level REA inspection

After Inspect class, enter a reported method name and overload index in the
chat composer, such as onCreate#0 (constructor notation is supported as
<init>#0), then tap Inspect method. The selected APK is still held only in
the active Android chat memory. An authenticated request to
POST /v1/apk/method/inspect sends the SHA-256 bound APK, X-REA-CLASS-NAME,
X-REA-METHOD-NAME and X-REA-OVERLOAD-INDEX. The isolated host executes only
REA's fixed inspect-android-method CLI against the selected APK; the APK itself
is never executed.

The bridge measures source excerpt and total source length in UTF-16 code
units for Android/Windows compatibility. It serializes JSON as UTF-8 without
unnecessary ASCII escaping, preserving bounded Unicode source text.

The reply includes the method signature, selected overload index, Java or
smali representation, decompiler fallback markers, complete/partial source
coverage, a bounded 6,000-character source excerpt and SHA-256 fingerprints
of the full original REA report and reported method source. If the source
was partial or truncated it is explicitly labeled. A reviewed Developer
source proposal may reuse at most 1,200 characters of this method excerpt
as untrusted data. The output never grants source write or install authority.

## Trace static method references

After inspecting a method, tap Trace references to ask the authenticated
REA host where the method is referenced in DEX using nodes. This calls
POST /v1/apk/method/references with the same selected APK SHA-256, exact
class name, method name and overload index. The host invokes only the fixed
REA trace-android-references command.

The response contains a bounded inventory of reported using nodes, a
count of any omitted results, original REA output SHA-256, and separate
position coverage indicating when direct invoke site details are partial,
unknown or not reported. It does not claim reflective calls, virtual
dispatch, runtime call sites or execution; it does not install or run
an APK. These read-only findings can be included as clearly untrusted
evidence in the existing Developer source review flow.

## AI-assisted implementation handoff

After REA evidence appears in chat, type a **specific Developer feature
request** into the existing composer (12–1200 characters), then select
**Prepare REA proposal**. Developer runs a fresh read-only source scan against
the canonical Developer branch, restricts files to PatchEngine's existing
allowlist, and hands a pinned-source request to PatchWorkbench.

REA output is **untrusted data**, not an instruction or write approval.
The existing diff fingerprint review, owner approval, single-attempt commit,
CI and separately gated signed-release publication are unchanged. Personal
J.A.R.V.I.S. is never the target of an REA-driven code proposal.

Changing task/target, navigating out, backgrounding, or restarting Developer
discards the transient evidence/proposal offer. Credentials remain encrypted
in Android Keystore. APK bytes are kept in memory temporarily, cleared after
analysis, and never written to the Android application's private storage.

## Tests and limitations

```sh
python3 -m unittest discover -s developer/tools -p 'rea_bridge_test.py' -v
./gradlew -PjarvisDeveloper=true :developer:testDebugUnitTest
```

The bridge's CI uses a **mock REA executable** to verify its upload contract,
hash binding, invocation, errors and refusal behavior. Android tests verify
HTTPS endpoint validation, evidence binding, and source-proposal framing.
A passing CI does **not** prove a particular user's REA/JADX host is installed,
reachable, correctly licensed or capable of analyzing a given APK. Test the
real bridge with a harmless APK on the target host before production use.

REA package's MIT license does not override third-party analysis-engine
licenses or legal restrictions on reverse engineering. Inspect software
you own or are authorized to analyze; avoid circumvention of protected
access controls and redistribution of proprietary recovered code.
