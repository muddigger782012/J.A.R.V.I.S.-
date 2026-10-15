# Cross-platform Jarvis architecture and rollout

Status: **approved product direction; design only, not implemented**.

## Target platforms
- Android remains the primary implementation, retaining direct APK and future Play Store distribution.
- Windows 10/11: native-feeling desktop assistant, voice, notifications and supported device actions.
- Windows 7/8.1: best-effort legacy client with a reduced, explicitly documented feature set. These operating systems are end-of-support; avoid weakening TLS, credential storage or backend security to accommodate them.
- iOS/iPadOS: chat, voice sessions, account/sync and supported shortcuts; background wake-word and system-wide controls are constrained by Apple policies.
- macOS: desktop assistant with supported voice, notifications and OS integrations.

## Design principles
1. Define a portable assistant protocol and core domain contracts for conversation, context, memory, intent/tool invocation, AI provider routing, authentication and synchronization. Keep Android Activity, AccessibilityService, Gradle and platform APIs out of portable contracts.
2. Implement thin platform adapters for microphone/STT/TTS, wake word, notifications, overlay/tray, permissions, accessibility/automation, filesystem and secure credentials. Capabilities must be discovered and reported, not assumed identical across OSes.
3. Prefer staged extraction of proven Android functionality over a rewrite. Evaluate Kotlin Multiplatform, Rust shared core, or a protocol-first client architecture through a short feasibility spike before choosing technology.
4. Sync only user-approved data with encrypted transport, authenticated accounts, explicit privacy controls, deletion/export, conflict resolution and offline queue behavior. Never place provider secrets in distributed clients.
5. Support platform-specific installers and updates (Android APK/AAB, Windows installer, Apple-approved distribution), signing, crash diagnostics and version compatibility. Preserve existing Android IDs, signing, data and update feeds.
6. Design a capability matrix and acceptance tests per platform. Avoid promising full Android-equivalent Accessibility, background microphone or system privileges on Windows and Apple platforms.
7. For Windows 7/8.1, explicitly test runtime and TLS compatibility and document unsupported capabilities; prefer a limited secure client or supported browser if full parity is unsafe.

## Suggested milestones
A. Audit Android assistant core and isolate protocol/contracts without behavior changes.
B. Prototype Windows 10/11 client against shared protocol; verify chat, voice and notifications.
C. Add optional encrypted account/device sync with consent and offline handling.
D. Prototype macOS and iOS clients within platform policy constraints.
E. Evaluate legacy Windows 7/8.1 support separately against maintenance and security costs.

## Release gates
- Independent platform build and integration tests, signed distribution, upgrade compatibility and security review.
- Explicit feature-parity matrix and physical-device verification per OS.
- Android functionality and release channels remain intact throughout migration.
