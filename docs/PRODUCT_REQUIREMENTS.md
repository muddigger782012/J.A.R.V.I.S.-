# J.A.R.V.I.S. product requirements and design decisions

Status: project requirements compiled from user discussions. This document is **not** proof that any feature is implemented. Validate against code, builds and device testing.

## Identity and experience
- J.A.R.V.I.S. is a voice-first Android assistant, with future Windows ambitions.
- Retain the existing Jarvis wordmark/label and Arc Reactor header/live-chat animation. Preserve artwork proportions, spacing and counter-rotating motion; changes require approval.
- Preserve Default, Stark, Cybertron, alternatives and additional gradient/Hacker palette variants where present. All app-owned surfaces follow the active theme.
- Main chat has a compact ChatGPT-inspired toolbar: plus menu for supported attachment/search actions, combined Settings/Tools control, and a separate diagnostics view. Do not let diagnostics replace chat.
- Settings should be structured, consistent, and free from loops. Appearance settings include Themes and Fonts (size/color); chat surface darkness adjustment is a desired control.
- Match themed menus, popup dimensions, embedded-browser top border, and dialogs to the active palette while retaining accessible contrast and touch targets.
- Preserve Android system-owned dialogs as system-styled.

## Voice and conversation
- Preferred default wake phrase: "Hey Jarvis"; prioritize sensitivity, false-trigger handling, visible mic/listening status and background stability.
- Allow choosing functional male/female voice options, previewing voice selection, and editing introduction and wake responses. No silent voice-selection failures or navigation exits.
- Improve speech-to-text and user-specific speech adaptation with informed privacy choices. Investigate offline models and licensing; never assume all neural voices are prepackaged.
- Keep live chat accessible in the app and, subject to Android permissions, through an outside-app overlay.
- Preserve conversational context within and across appropriate sessions with transparent persistence and privacy controls.
- Provide copy/export of live chat for user-directed debugging with sensitive-data warnings.
- Support online AI fallback and useful offline commands. Avoid claims of unrestricted self-learning or autonomous system privileges.

## Intelligence and search
- Maintain a clear, updateable Intelligence Plan with real progress markers.
- Support local command classification with validation before learning; add safe fallbacks when unsupported.
- Embed search results in chat where technically and legally possible, including images/video previews when supported. Choose search APIs based on real pricing, quotas, terms and licensing, not assumptions about browsers being search APIs.
- Device actions (calendar, navigation, phone, weather, reminders) must use supported Android APIs, explicit user authorization and permission checks.

## Beta and distribution
- Keep the main and Beta apps separately identifiable; document update channels, package IDs and signing.
- Beta should have an onboarding tutorial and required permissions guidance; avoid embedded developer API secrets.
- Voice packs and models may require staged download because of APK size. Verify availability and integrity before claiming out-of-box functionality.
- The user wants reliable in-app downloads/updates and compatible installations without manual file-manager steps when Android permits.

## Approval and evidence
- User instructions govern product choices; a design preference is not a claim of completed implementation.
- Keep screenshots, verified device observations and acceptance criteria alongside feature changes.
- Protect the current release: no unrelated refactors or signing changes during release stabilization.
