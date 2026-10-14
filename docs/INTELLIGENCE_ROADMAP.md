# J.A.R.V.I.S. intelligence and modular update roadmap

Status: proposed development roadmap; no phase is declared completed by this document.

## Immediate release stabilization
1. Inspect the current implementation and open pull requests.
2. Validate menu navigation fixes, voice settings, bundled voice integration, intelligence progress UI and conversation context behavior.
3. Run reproducible builds, tests and on-device upgrade checks; distinguish debug from release APK.
4. Ship only after verifying signing, data preservation, core features and regression coverage.

## Seven-phase modular update and optimization plan
1. **Build optimization:** Gradle caching, incremental work, separate CI paths for APK and content-only updates, reproducible artifacts.
2. **Modular intelligence:** versioned command/behavior/config/knowledge/model assets with clear interfaces; keep code changes in APK releases.
3. **In-app modular updater:** authenticated metadata, signed manifests, downloads, integrity verification, compatibility checks and user-visible status.
4. **Versioning and rollback:** atomic installs, previous-known-good packages, failure recovery and migration strategy.
5. **Core APK updates:** preserve package identity, signing lineage, settings, user data and upgrade compatibility.
6. **Performance and storage:** lazy-load large models, deduplicate assets, measure startup/CPU/RAM/battery/storage and keep offline core usable.
7. **Reliability and compatibility:** cryptographic checks, device/Android version compatibility, offline recovery, backups, wake-word continuity and regression tests.

## Intelligence milestones (measure, do not invent percentages)
- Stable wake-word and listening-state instrumentation.
- Reliable voice selection and speech recognition; evaluate user-adaptation methods.
- Bounded conversation-context retention with privacy and data migration.
- Offline intent routing, validated learning and online fallback.
- Embedded search results with source attribution and media support.
- In-app progress dashboard reflecting merged, tested milestones.

For each milestone record: owner, branch/PR, acceptance tests, observed device behavior, status (proposed/in progress/implemented/verified/released), and blocker. Do not label a milestone released based only on a commit or passing CI.
