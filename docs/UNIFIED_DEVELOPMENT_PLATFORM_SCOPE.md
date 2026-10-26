# J.A.R.V.I.S. Unified Development Platform — Implementation Scope

Status: Approved product scope; implementation and verification are tracked separately. No feature is complete merely because it appears here.

## Applications and distribution
- Personal J.A.R.V.I.S. Assistant is the voice/conversation controller.
- Personal J.A.R.V.I.S. Developer is the separate engineering execution app.
- Preserve all existing chat, wake word, microphone, HUD, themes, settings, updates, widgets, and diagnostics.
- Commercial editions exclude Owner Development at UI, IPC, policy, and execution layers.

## Approval and privilege policy
- Supervised: require approval for consequential jobs.
- Authorized Autonomous: execute within explicitly approved task scope.
- Owner Development: personal editions only, with owner-defined standing authorization and audit log.
- Approval does not create root, signing, Android permissions, or external service credentials.
- Support authorized root/rootless, Shizuku, ADB, and managed-device mechanisms when technically available.
- Require specific confirmation for destructive, publication, credential, and device-wide actions.

## End-to-end voice control
- Natural language creation, diagnostics, repair, testing, building, and release preparation.
- Conversational follow-up, voice project switching, status queries, task pause/resume/cancel.
- Voice approval of reviewed change sets, with stronger confirmation for sensitive actions.
- Structured versioned authenticated Assistant–Developer IPC, capabilities handshake, job identifiers, progress events, and explicit error reporting.
- Offline command queue with idempotent replay and user-visible state.

## Engineering execution
- Persistent task queue and recovery across app/process restarts.
- Repository inventory, source editing, isolated branches, diffs, commits, and PR workflow.
- Remote build runner plus local build capability when available.
- Gradle/Android SDK, dependencies, unit/instrumentation/UI/emulator testing.
- Diagnostics: microphone/wake word, logs/crashes, performance, app health, connectivity, device checks, visual screenshots/recordings with consent.
- Failure analysis, bounded repair-and-retest loops, dependency/vulnerability monitoring.
- Cross-app registry and task routing for Assistant, Developer, Keyboard, Widgets, Connection Manager and future apps.
- Per-project requirements, known defects, approved decisions, source revision and build history.
- Secure credential/signing vault; least-privilege scopes and secret redaction.

## Distribution and device lifecycle
- Build debug/release APK and AAB; manage package names and version codes.
- Persistent signing identities; verify signatures and compatibility.
- Built-in installer with package inspection, permission review, consent and installation failure diagnostics.
- In-app updates and channels, staged rollout, prior release archive, recovery and rollback plans.
- Test on emulator and physical devices; post-install health verification.
- Export source, APK/AAB, logs, tests and backups.

## Verified completion gate (per job)
1. Source changes committed — record repository, branch and immutable commit SHA.
2. Build successful — record runner, build ID, logs and exit status.
3. Automated tests passed — record suites, test counts and reports.
4. Signed artifact produced — record package ID, version, digest and signing verification.
5. Installed and device-tested — record target device, install result and acceptance checks.

Each gate must be PENDING, RUNNING, PASSED, FAILED or BLOCKED; include evidence and timestamps.
Never infer success from a plan, commit, generated code, or UI text. Never report publication or installation without verified evidence.
A task is COMPLETE only when every required gate is PASSED. If device installation is intentionally excluded, mark it NOT APPLICABLE with owner-approved scope and report 'build verified', not 'device verified'.

## Implementation sequence
1. IPC and task ledger; approval enforcement; source inventory; regression baseline.
2. Build/test runners, diagnostic adapters, bounded repair loops and evidence collection.
3. Signing, installer integration, updates, snapshots, rollback and device acceptance.
4. Voice workflows, cross-app orchestration, offline recovery and dashboard.
5. Commercial packaging enforcement, security and regression validation.

## Acceptance criteria
- Assistant voice command creates exactly one traceable Developer job.
- Job survives process death and resumes without duplicate writes.
- User can request accurate progress and inspect evidence in either app.
- Commercial builds cannot select or invoke Owner Development.
- No app is declared released from a commit alone.
- Existing app features remain intact under regression tests.
