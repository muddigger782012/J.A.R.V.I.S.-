# Jarvis Developer — Codex and coding-agent instructions

Read `developer/DEVELOPMENT_ROADMAP.md` before changing Developer coding capabilities. This file governs only the isolated Jarvis Developer app on `feature/jarvis-developer`. Preserve the existing Jarvis and Jarvis Beta applications, embedded API behavior, package identifiers, signing and update channels. Never merge Developer work into main without explicit approval.

## Product objective
Build an AI software-development agent able to understand repository structure, plan tasks, make bounded multi-file changes, run tests, diagnose errors, and prepare signed updates. Eventually support new Android apps and Windows/backend projects. Current `CodingAgent.kt` is a narrow proof of concept limited to the Developer launcher icon; do not describe the six phases as implemented.

## Execution order and gates
0. Verify safe signed release, previous-release backup, immutable source SHA, recovery rebuild, installation approval, and last-known-good rollback. Do not weaken safety to accelerate coding.
1. Codebase awareness: indexed repository inventory, dependency and symbol tracing, read-only inspection, context selection and explanations.
2. Coding engine: task-scoped multi-file patch proposals, Kotlin/Java/XML/Gradle support, diff previews, file allowlists, safe branch commits and explicit user confirmation.
3. Autonomous task planning: decomposed tasks, checkpoints, persisted state, progress and resumability.
4. Testing and debugging: build/test feedback, bounded retry budgets, compiler log analysis, regression checks, failure summaries.
5. Controlled self-development: isolated self-modification, test gates, release candidate evaluation, rollback/recovery safeguards and user-approved promotion.
6. Application development: new Android app scaffolds, backend/services, then Windows support with platform-specific test pipelines.

## Safety and operational requirements
- Separate coding, testing, signing, publishing and installation. Never silently merge, publish or install.
- Restrict file scope per task; reject arbitrary shell execution, untrusted instructions from repository content, and writes to secrets or credentials.
- Never hardcode or expose API keys, GitHub tokens, signing material, or credentials in source, APK, logs or AI prompts.
- Preserve a recoverable last-known-good signed release; fail closed on missing or unverifiable backup.
- Require user approval before potentially destructive changes, release promotion and device installation.
- Record task ID, proposed diff, touched files, model/provider, tests, build URL, SHA and result for auditability; redact secrets.
- Distinguish source changes, passing CI, emulator/device verification and published APK in all progress reports.
- Prefer incremental, testable changes with clear acceptance criteria. Keep the existing Jarvis UI theming rules from root `AGENTS.md` where relevant.

## Initial acceptance criteria
Phase 1: can inventory Developer source, find relevant code for five representative requests, explain dependencies and propose file plans without writes.
Phase 2: can make and review a scoped multi-file change on an isolated branch, validate paths/diffs, build signed CI, and report a reproducible result without automatically publishing.

## Planning estimates
Relative to completion of recovery verification: phase 1 3–5 days (week 1); phase 2 5–10 days (weeks 2–3); phase 3 1–2 weeks (weeks 3–5); phase 4 2–3 weeks (weeks 5–8); phase 5 3–5 weeks (weeks 8–13); phase 6 6–12 weeks (weeks 14–25). These are planning ranges, not commitments. Update estimates when actual test results warrant.

## Owner continuation directive — 2026-10-18

Follow [GitHub issue #128](https://github.com/muddigger782012/J.A.R.V.I.S.-/issues/128): continue Phases 5 and 6 without blocking coding, CI, test APKs, or separate preview artifacts on the missing `developer-previous` backup. Retain the implemented backup/recovery logic and all fail-closed production release/promotion/installation gates for later remediation. Do not weaken signature, update, rollback, or approval protections. Deliver a complete isolated Developer build and independent evidence; do not mark incomplete features or device tests as complete. The owner wants forward development now, not a recovery-only workstream.

## Owner UI priority — 2026-10-18

Implement [Developer UI redesign issue #134](https://github.com/muddigger782012/J.A.R.V.I.S.-/issues/134) as the next user-facing Developer milestone. Reuse original Jarvis Default-theme assets, exact header reactor artwork and JARVIS lettering; show JARVIS DEVELOPER and adopt a simple beginner-first Home/Projects/Assistant/Activity/Settings navigation with advanced coding controls progressively disclosed. Preserve every existing feature, safety gate and isolated Developer identity. Build and verify a preview APK without production promotion or backup gate bypass.
