# Codex development workflow

Goal: maximize correct, verified changes per unit of Codex usage.

1. Read AGENTS.md and the prioritized development backlog. Inspect relevant source and existing PRs before editing.
2. Work on one approved, bounded task with explicit acceptance criteria. Do not infer new scope from repeated 'continue' prompts.
3. Prefer targeted file searches, reusable components, and narrow patches. Avoid unrelated refactors or rebuilding unchanged code.
4. Run fast targeted checks while iterating; run applicable regression, theme, voice and emulator checks before merge. Do not bypass failures to claim success.
5. Preserve Jarvis and Beta package identity, signing, persisted data, updater and existing API behavior. Jarvis Keyboard is a separate APK and its source is not on the distribution branch.
6. Classify substantial features as core Jarvis, shared technology, or separate APK candidate. Ask for approval before splitting products.
7. Stop after repeated identical failures and report the exact blocker, logs, and smallest next step.
8. Finish with branch/PR, changed files, acceptance criteria, test results, device verification status, unresolved issues and next recommended task.

Product planning and acceptance decisions belong upstream of Codex implementation; tests and release verification remain mandatory.
