# J.A.R.V.I.S. (regular/Beta) ↔ J.A.R.V.I.S. Developer: direct on-device link

## Scope

This integration is for the **regular J.A.R.V.I.S. Beta APK**, package
`com.assistant.core.beta`, with the independent **J.A.R.V.I.S. Developer**
APK, package `com.jarvis.developer`.

It is **not** the Personal J.A.R.V.I.S. AI-provider HTTP bridge or an alternate
AI model endpoint. Personal J.A.R.V.I.S. (`com.assistant.core`) remains
independent and does not expose this Beta-only connection screen.

## What is implemented

- Beta Settings → **J.A.R.V.I.S. Developer** opens one private, themed page.
- **Verify secure connection** performs an actual Binder capability handshake.
- **Send review request** submits a bounded task description as a retained,
  idempotent review draft. Acknowledgement means queued, not approved or done.
- **Latest Developer job** reads the authenticated task checkpoint and its
  actual verification stages; it does not infer build/test/publish success.
- **Open J.A.R.V.I.S. Developer** launches the known Developer component
  after verifying the installed package and matching signing identity.
- Chat supports **Connect to Jarvis Developer** and the existing explicit
  `Developer task: <description>`, `developer job status`, and job controls.

The shared Android Binder protocol remains `DevelopmentBridgeContract.VERSION=1`.
It uses an explicit Developer component, a signature-level permission and the
calling UID/package/signature checks on both ends. Both apps must be installed
on **the same Android device** and signed with compatible certificates.
A debug APK signed differently from the installed release cannot use this
bridge; use a correctly signed pair, not a security bypass.

No cloud server, pairing token, shared API key, account login, or local network
port is involved. The service is bound on demand; verification does not create
a permanent connection or background process.

## Authorization boundaries

- Commercial Beta cannot use `owner_development` mode.
- A review draft cannot authorize a source write, shell command, arbitrary
  install, signing, publication, privileged action or recovery override.
- APK installation and installed-package preflight bridge operations remain
  Personal-only. The Beta connection screen does not expose them.
- Developer must independently validate its repository context, job scope,
  planned diff, approval, build/test evidence and any release/install gates.
- Sending diagnostics or a task can carry user-entered content between apps.
  Never paste passwords, API keys, GitHub tokens, or signing data.

## Device acceptance checklist (pending a compatible signed build)

1. Install regular/Beta J.A.R.V.I.S. and Developer using their established
   compatible signing identities.
2. Open regular J.A.R.V.I.S. → Settings → J.A.R.V.I.S. Developer.
3. Tap Verify secure connection. Require a verified identity/capability response,
   not merely that Developer opens.
4. Submit one harmless 12–1500 character review, confirm its job ID and
   queued state; check that the same job appears in Developer.
5. Read Latest Developer job. Confirm that unverified stages stay PENDING/BLOCKED.
6. Tap Open J.A.R.V.I.S. Developer. Confirm the independent app opens.
7. In a mismatched-signer or missing-Developer test, confirm fail-closed status,
   with no unauthenticated fallback or false success.
8. Confirm `com.assistant.core` (Personal) has no Beta connection entry and
   commercial mode cannot enable Owner Development.

Source commits and automated tests are not equivalent to device acceptance or
publication. Sign/release the APKs only through their existing protected
pipelines and verify the resulting version and signing certificate.
