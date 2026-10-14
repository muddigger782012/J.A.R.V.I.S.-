# Jarvis feature ownership and standalone APK decisions

## Product boundary
Jarvis is a standalone AI assistant. A future commercial ecosystem may include other independently installed applications. **Jarvis Keyboard already exists** as a separate APK; preserve it and do not rebuild it as part of Jarvis. A dedicated launcher/system UI is a *future candidate*, not part of the current assistant scope.

## Decision process
For each substantial feature or update, classify as **Core Jarvis**, **Shared technology**, or **Standalone APK candidate**. Include the following in implementation PRs:

| Check | Question |
| --- | --- |
| User value | Is it an assistant capability or a separate end-to-end product? |
| UX | Does it need its own home screen, onboarding or default-app role? |
| Permissions | Can permissions remain isolated to the product that needs them? |
| Dependencies | Can the feature operate when Jarvis is not installed? |
| Resources | What is the impact on APK size, storage, CPU, RAM and battery? |
| Maintenance | Does it require an independent update/release cadence? |
| Commercial | Would separate pricing, support or distribution make sense? |
| Compatibility | Can existing installations, API behavior and user data be preserved? |

**Do not create a new APK automatically.** Flag the candidate, explain the tradeoffs, and obtain user approval.

## Current classification
| Feature | Classification | Decision |
| --- | --- | --- |
| Jarvis intelligence, conversational context and memory | Core Jarvis | Keep in assistant |
| Wake word, voice, personality and assistant speech | Core Jarvis | Keep in assistant; reusable interfaces allowed |
| Ordinary voice-driven device commands | Core Jarvis | Keep in assistant |
| Provider clients, speech processing contracts, validated inference helpers | Shared technology | Extract only when useful, without breaking installs |
| Jarvis Keyboard | Existing standalone APK | Maintain separately; integrate optionally |
| Full launcher and system-wide UI | Standalone APK candidate | Do not implement inside assistant by default |
| Heavy equipment/automotive diagnostic suite | Standalone APK candidate | Separate proposal when requirements emerge |
| Authentication and AI usage gateway | Shared backend service | Not an APK |

## Jarvis Keyboard integration rules
- Locate and inspect the existing keyboard repository and build/release workflow **before editing it**; do not guess its source location.
- Preserve existing keyboard package ID, signing certificate, settings, clipboard, input-method behavior, updater and published APK compatibility.
- Make AI and speech integrations optional and versioned. Keyboard input must work independently of Jarvis and when offline.
- Avoid exposing keyboard text, clipboard contents or credentials to Jarvis or a cloud service without explicit user action and clear disclosure.
- Keep keyboard-specific UI, prediction, layout, sizing, emoji, clipboard and dictation improvements in the keyboard project, not in Jarvis Assistant.

## Codex workflow
1. Before substantial feature implementation, identify its classification and document any possible separate APK.
2. If shared code is proposed, specify the interface, dependency direction and compatibility tests.
3. If an independent APK is proposed, provide user-facing tradeoffs and wait for approval.
4. Include classification in PR summary, alongside build/test results and any unverified physical-device behavior.
5. Coordinate this policy with `docs/COMMERCIAL_ARCHITECTURE.md` and existing PRs; do not overwrite ongoing Codex work.
