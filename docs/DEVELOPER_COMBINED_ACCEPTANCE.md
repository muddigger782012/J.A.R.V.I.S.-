# Combined Developer repository-aware acceptance candidate

This integration preserves current main, the latest canonical Developer branch, and the completed repository-context implementation from **Ground Home coding requests in verified repository context**. Reconciliation is by ancestry and tree comparison, not a replay of obsolete development commits. Main, canonical Developer, signing identity, updater and recovery releases are not changed by preparing this branch.

The older transient-credential pull request is already represented in current source: bounded atomic admission, identifier/control-character checks, cleanup, native contracts and newer read-only credential rebinding are retained. The older Settings/Tools native-harness checkpoint is not imported over the tested four-tab interface and current acceptance tools. Existing Chat and Personal/Beta production code remain intact.

## Real Gemini acceptance

The opt-in `Live Gemini repository context acceptance` workflow uses the repository secret `GEMINI_API_KEY`. The key is never entered into source, workflow inputs, logs or reports. It runs the existing Kotlin repository reader, context builder and actual Gemini HTTPS client, without fixture transports. Source comes from the authorized canonical Developer branch at the immutable commit actually resolved by that request; this is recorded separately from the candidate build commit.

A successful test must verify actual source bytes and receive a decoded Gemini response citing both the full source commit and an exact supplied path. The bounded evidence records metadata, hashes, outcome and elapsed time, not the source excerpts or model response. Missing credentials are **BLOCKED**, never a passing/skipped live test. Provider, network or grounding failures fail the test. Ordinary builds exclude this live source set and require no API key.

The equivalent local command uses the explicit `developer/tools/live_gemini.init.gradle` init script and selects `com.jarvis.developer.LiveGeminiRepositoryContextTest`; configure credentials through a secure environment binding. Do not paste credentials into a terminal command or chat.

## Samsung preparation and distribution boundary

Prepare a release-variant APK with the existing unsigned build-only override, inspect its package/version/digest, and keep the production signing identity unchanged. An unsigned or debug APK is not an upgrade-compatible replacement for an installed production Developer APK. The trusted signing controller restricts automatic candidate scope; broad integration changes must receive the required canonical-source approval before that controller can sign them. Do not bypass its protected-file restrictions.

Production signing, publication and installation remain separately controlled. The publication workflow also changes release/updater metadata and must not be dispatched merely to obtain an APK. Preserve the current signed Developer release and its immutable recovery snapshot. Physical Samsung S26 Ultra acceptance and live API verification must be reported independently; neither follows from an emulator result or successful compilation.
