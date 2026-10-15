# Jarvis Developer Phase 1: codebase awareness

Status: approved
Scope: source-only implementation on feature/jarvis-developer. Owner explicitly deferred recovery verification on 2026-10-15. Publishing, installation, signing identities, credentials configuration, release channels and normal Jarvis/Beta behavior remain outside this change.

Implement a bounded, GET-only repository reader and deterministic lexical analyzer. Pin source to one full commit SHA; verify blob identities; restrict paths and file/response/context budgets; never interpret repository instructions as permissions or send source to a model. Provide inventory, scoped line browsing, relevant source/symbol/dependency selection, candidate file plans and impact notes with auditable citations.

Expose these capabilities through separate read-only controls in Developer. Preserve the existing explicitly invoked icon-editing proof of concept and all release/install approval gates. The read-only reader has no write method and does not invoke that coding path.

Validate menu restructuring, Appearance scrolling, AI connectivity diagnosis, per-chat context and Intelligence Plan progress against the actual Actions checkout. Assert real files, declarations, dependencies, Git blob/commit/line references, and unchanged source snapshots. Add boundary tests for scope/path/revision errors, symlinks, response integrity, redirects, authentication/rate limits and bounded reads. Run Developer unit tests and debug assembly, plus signed candidate CI without promotion. Device and visual verification are separate pending evidence.

Recovery remains unverified. Keep historical 403/401 and missing-backup findings in developer/DEVELOPMENT_ROADMAP.md. Do not dispatch the manual publication workflow, create releases, install APKs, alter secrets or infer recovery completion from a passing build.
