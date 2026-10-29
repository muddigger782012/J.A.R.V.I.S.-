# Jarvis Developer: autonomous self-development bootstrap

Status: approved
Priority: critical
Target: first functional release after signed release candidate

## Acceptance test
User says: "Use the original Arc Reactor artwork from the main Jarvis Assistant header as your own launcher icon. Commit, build, diagnose failures, and present the update for my approval."

Success requires actual repository discovery, plan, editable source changes, CI verification and installable signed release candidate. A canned icon replacement is not acceptance.

## Agent loop
1. Read AGENTS.md, repository tree, current task and relevant source.
2. Use a selectable AI provider (Gemini or OpenAI) to generate a structured change plan and edits; require API credentials supplied securely at runtime, never bundled in APK.
3. Read source files through GitHub API. For icon task discover app/src/main/java/com/assistant/core/ArcReactorView.kt and referenced drawable arc_reactor_art, including binary asset support.
4. Write actual changed resources/manifest on isolated feature branch; never edit main or production modules.
5. Start or observe GitHub Actions build; collect job conclusions and logs.
6. On failure, feed bounded error excerpts back to the model and retry at most 3 times, keeping change history visible.
7. On success show diff, commit, build result and artifact. Require explicit approval before merge, signed release publication or installation.
8. Maintain task state across restarts and avoid redoing already completed steps.

## Security and reliability
Use a secure backend or OAuth/GitHub App credential flow for GitHub write access; do not embed PATs or API keys. Validate repo, branch, path allowlist, commit SHA, package ID and signed release provenance. Enforce budgets, max retries, approval gates, and cancellation. Android cannot silently install APKs without special privileges. Retain original Assistant/Beta and Keyboard release channels.

## Required milestones
A. Authenticated repository reader and write operations, binary assets, file diffs and branch management.
B. Gemini and OpenAI model adapters, coding prompts, patch validation and task state machine.
C. CI trigger/poll/log retrieval, retry controller and signed artifact discovery.
D. First autonomous Arc Reactor icon test, with user review before release.
E. Extend to sequential development-plan execution and app self-updating.

## Not yet implemented
Current APK has a canned self-icon operation, basic GitHub reads, OpenAI-only request and manually checked builds. It cannot perform the acceptance test autonomously.
