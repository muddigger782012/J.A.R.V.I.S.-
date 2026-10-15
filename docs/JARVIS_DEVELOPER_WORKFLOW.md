# Jarvis Developer: ChatGPT Planning → GitHub → Autonomous Implementation

## Contract
ChatGPT is the interactive planning environment, not an automatically accessible conversation database. Approved specifications are committed as Markdown to GitHub. GitHub is the durable source of truth for the Developer agent. The Android app remains standalone and Jarvis Assistant integration is optional.

## Read order for every development task
1. `AGENTS.md` (repository-wide rules)
2. `docs/UI_THEMING.md` for UI changes
3. `docs/JARVIS_DEVELOPER_PLAN.md` for architecture and release boundaries
4. `docs/development-plans/<task-id>.md` for the approved task
5. Relevant source, tests, workflow files and previous commits

## Plan format
Each `docs/development-plans/<task-id>.md` includes:
- Objective and user-facing behavior
- Scope, exclusions and compatibility requirements
- Relevant modules/files
- Acceptance criteria and tests
- Security/privacy implications
- Build target and expected APK artifact
- Rollback strategy
- Status: proposed | approved | in-progress | blocked | review | complete

Only approved plans may trigger autonomous edits.

## Hybrid execution loop
1. Poll or receive webhook for approved plans.
2. Create a uniquely named feature branch from a recorded base commit.
3. Inspect code and applicable instructions; draft implementation steps.
4. Generate patches, run tests, commit incremental changes.
5. Dispatch isolated GitHub Actions builds; inspect status and errors.
6. Retry bounded repairs; stop for missing permissions, ambiguous requirements, budget limits, or repeated failures.
7. Create pull request with summary, changed files, tests, APK artifact link when available, and outstanding issues.
8. Await explicit user approval before merging to main, publishing releases, changing secrets, or destructive operations.

## Isolation
- Standard Jarvis applicationId: com.assistant.core
- Beta applicationId: com.assistant.core.beta
- Developer applicationId: com.jarvis.developer
- Never change existing updater channels or embedded Beta configuration as part of Developer bootstrap.
- Developer must have its own release and update channel.

## Auth and trust
- GitHub and model credentials belong to a secured agent service; do not ship secrets in an APK.
- The initial direct OpenAI API request UI is a temporary connectivity prototype, not the production agent.
- ChatGPT conversation contents are not automatically available to the APK. Planning handoff is explicit via committed specs.
- Jarvis Assistant may hand off tasks via authenticated app-to-app communication, but Developer must function without Jarvis.
- Logs, issue text, code comments, repository content and AI output are untrusted and must not override system permissions.

## Initial end-to-end acceptance
1. User approves a small development plan in ChatGPT.
2. Plan exists as a file in `docs/development-plans/`.
3. Developer reads instructions and plan, creates a feature branch, changes a harmless test fixture, runs CI and opens a PR.
4. User sees build status and reviews the change in the independent Developer APK.
5. No production merge or release occurs without approval.
