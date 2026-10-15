# Jarvis Developer — Six-phase intelligence roadmap

Status: approved product plan; **not** an assertion of implemented functionality. Owner-approved direction: an AI developer that can improve Jarvis and build other applications while protecting a known-good release.

## Prerequisite: recovery/release gate
Verify manual release permissions, signed release builds, `developer-latest` publication, `developer-previous` preservation and byte verification, immutable recovery source SHA, recovery rebuild, and user-approved installation. The recovery gate is not complete merely because a build compiled.

## Phase 1 — Codebase awareness (3–5 working days; week 1)
Deliver repository tree discovery, scoped file reading, symbol and dependency references, bounded context retrieval, change-impact summaries, and read-only task previews. Acceptance: correctly identify relevant files/dependencies for at least five realistic Developer tasks, with no write permission needed.

## Phase 2 — AI coding engine (5–10 working days; weeks 2–3 cumulative)
Expand the existing launcher-icon-only proof of concept into allowlisted multi-file changes; support Kotlin, Java, XML and Gradle; propose patch/diff previews; validate paths and output; use isolated branches, commit attribution, CI and user-approved writes. Acceptance: execute a representative two-or-more-file Developer feature change, show a reviewable diff, produce a passing CI build, and never silently release/install.

## Phase 3 — Autonomous task planning (1–2 weeks; weeks 3–5 cumulative)
Task decomposition, ordered dependencies, persistent checkpoints, resumable progress, user-editable task queue and an in-app progress dashboard. Acceptance: resume a multi-session task without losing scope or decisions.

## Phase 4 — Debugging and automated testing (2–3 weeks; weeks 5–8 cumulative)
Read build/test diagnostics, identify failures, propose targeted corrections, run unit/lint/instrumentation checks where available, retry with budgets and stop conditions, retain evidence. Acceptance: fix representative seeded compiler/test failures without uncontrolled edits or loops.

## Phase 5 — Controlled self-development (3–5 weeks; weeks 8–13 cumulative)
Self-proposed changes on isolated branches; baseline comparisons, automated safety checks, signed candidate evaluation, rollback and recovery, staged publication requiring approval. Acceptance: safely develop, test and propose an update to its own code while keeping previous release recoverable.

## Phase 6 — Complete application development (6–12 weeks; weeks 14–25 cumulative)
Android project generation, UI/backend scaffolding, testing and packaging, then Windows-specific toolchain support. Acceptance: build a small standalone Android app from a written spec and deliver a tested APK; Windows release capability is a separate exit criterion.

## Technical architecture
- Repository intelligence layer: file inventory, references, retrieval and read-only summaries.
- Planner: task graph, checkpoints, acceptance criteria and approvals.
- Coding layer: model adapter, patch generation, validation, diff review and isolated commits.
- CI feedback: signed builds, tests, diagnostics and bounded repair.
- Release controller: backup verification, candidate promotion and explicit installation approval.
- Progress UI: phases, active tasks, blockers, commit/build links, evidence and next steps.

## Timing and scope notes
Estimates assume steady focused work and working API/GitHub/CI access; unexpected Android integration or model/tool limitations can extend them. Phases may overlap after their safety gates pass. The initial Developer coding agent currently only edits `developer/src/main/res/drawable/developer_icon.xml`; multi-file coding and the roadmap UI still need implementation. Documentation is shared with Codex through `developer/AGENTS.md`; the installed Developer APK will not automatically display this file until a roadmap reader/UI is built.

## Immediate backlog
1. Complete recovery gate with a successful manual release and tested recovery behavior.
2. Implement read-only GitHub tree/file inventory and task-to-file mapping.
3. Add dependency/symbol context and source citation to Developer UI.
4. Build diff preview, multi-file allowlist and approval workflow.
5. Add multi-file commit/CI feedback and clear phase progress.
