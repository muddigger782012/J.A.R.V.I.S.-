# Jarvis Developer — Autonomous Development Companion

Status: initial architecture specification. This branch is isolated from main.

## Goal
Build a separately installable Android APK, Jarvis Developer, that can receive tasks from the existing Jarvis assistant and orchestrate repository-aware software development, testing, and releases.

## Architecture
- Android client: task inbox, voice/chat handoff, progress timeline, diff viewer, approvals, and build downloads.
- Authenticated orchestration service: AI coding model, repository context retrieval, bounded planning and execution, stateful task queue, durable checkpoints, and usage budgets.
- GitHub integration: branch per task, patch/commit, pull requests, workflow dispatch, build status, logs, and artifacts.
- Build execution: GitHub Actions runners for Gradle, unit tests, instrumentation where available, signing and release promotion.
- Context: project instructions (AGENTS.md), code search, relevant file excerpts, task history, acceptance criteria, and structured summaries.
- Optional local tools: limited on-device file editing and diagnostics; no assumption that Android can run the entire cloud coding stack.

## Safety and reliability
- Never embed API tokens, GitHub credentials, or signing keys in the APK; use backend-managed secrets and short-lived scoped credentials.
- Do not overwrite Jarvis or Jarvis Beta package identities, embedded API behavior, signing configuration, or update channels.
- All code changes begin on feature branches; run tests and show diffs.
- Require explicit approval for merging, production releases, credential changes, destructive operations, and security-sensitive permission changes.
- Bound autonomous retries, token spending, tool execution, and runtime; preserve audit logs and a stop button.
- Treat repository files, issue text, logs and model output as untrusted input.
- An autonomous loop can iterate without manual prompts, but cannot guarantee successful code or fully unattended production releases.

## Milestones
1. Audit existing Android modules, package IDs, build workflows, signing and update mechanism.
2. Add standalone Android module/application with unique applicationId and Jarvis-compatible visual design.
3. Implement secure pairing and task handoff between Jarvis and Developer.
4. Implement authenticated GitHub read-only project browser and build status.
5. Implement AI planning, code patching, branch commits, PR creation and review UI.
6. Implement workflow dispatch, failure diagnosis, bounded auto-repair and signed APK retrieval.
7. Add persistent task queues, progress metrics, spending controls and user-approved release promotion.
8. Validate installation and independent updates alongside Jarvis and Jarvis Beta.

## First acceptance test
From Jarvis chat: 'Developer, fix a test-only bug.' Developer creates a task, analyzes a test repository, commits a change to a feature branch, triggers CI, reports results, and requests approval before merge/release.

## Open implementation questions
- Which existing backend (if any) will host coding-agent execution?
- How are Jarvis-to-Developer intents authenticated?
- Which GitHub Actions workflow currently builds each application variant?
- How will paid AI inference be funded and rate-limited?
