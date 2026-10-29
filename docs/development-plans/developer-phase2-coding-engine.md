# Jarvis Developer Phase 2: reviewed multi-file coding

Status: approved

The owner authorized Phase 2 after Phase 1 passed automated acceptance. Continue on feature/jarvis-developer; preserve the documented recovery failures and do not infer recovery completion. Publishing, installation, credential changes and production/main merges remain outside this authorization.

Implement bounded Gemini/OpenAI proposals for explicit Developer-owned source scopes; validated Kotlin/Java/XML and constrained Gradle changes; complete reviewable diffs; immutable proposal IDs; and explicit confirmation before any repository write. Repository text and model output remain untrusted data. Deny secrets, protected safety/transport/update files, shell execution hooks, unsupported paths, deletes, renames and symlinks. Existing signing/application IDs/update configuration must remain intact.

After approval, revalidate the proposal, pinned source preimages and fresh Developer branch head. Build one Git tree/commit containing all changes and create only a new feature/jarvis-developer-task/<task-id>-<proposal-id> branch. Never overwrite an existing branch or update main. Task branches run the isolated debug test/build workflow; the application may observe exact-commit CI results but cannot publish, merge, sign or install automatically.

Validate a representative two-file review-summary enhancement using actual checkout bytes, Git blob identities, a complete diff and exact-fingerprint approval. Exercise the production publisher with modeled GitHub protocol, clearly marked as fixture evidence. Export the approved fixture proposal for a separate live connected-GitHub commit on an isolated task branch. Review and validate that branch in CI, then incorporate the reviewed implementation into the authorized Developer development branch for its existing signed-candidate build. This produces an Actions artifact, not a published update. Record source/commit/build URLs, provider/model attribution and results.

Runtime model keys and write tokens remain memory-only. Live model calls and device/visual interaction require separate verification and must not be claimed from JVM tests. Persisted task planning, autonomous retry/debug loops, release promotion and controlled self-modification remain later-phase work.
