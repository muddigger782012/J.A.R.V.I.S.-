# J.A.R.V.I.S. visual development rules

All new UI and visual changes must follow the user's currently selected theme.

- Read `docs/UI_THEMING.md` before changing visual code or resources.
- Use `jarvis_*` theme attributes for surfaces, text, icons, borders, highlights and corner geometry. Resolve them from the displayed themed context; do not pin a feature to Default, Stark or Cybertron.
- New activities must extend `ThemedActivity`. App-owned dialogs must use `ThemedDialogBuilder`, which preserves show/dismiss listeners and applies font preferences.
- Reuse the composer icon backgrounds for matching toolbar/header buttons. Popup surfaces must use the active panel colors, stroke and `jarvis_corner`, with readable opaque backing.
- Respect font preferences, large text, scrolling, narrow screens, touch targets and content descriptions. Keep JarvisLabelView and ArcReactorView artwork intentional.
- Run `python3 scripts/check_ui_theme.py` before submitting visual changes. Do not expand its legacy baseline to hide new violations: migrate touched styling to theme attributes instead.
- Verify Default, Stark, Cybertron, their alternatives and the additional palettes. Report source/build checks separately from actual device or emulator visual verification.
- Android-owned permission, installer and picker windows follow Android's styling. Do not promise control over their appearance.

These requirements apply to future additions as well as modifications.

## Mission and decision authority
J.A.R.V.I.S. is an Android-first, eventually Windows-capable, voice-driven assistant with online AI and useful offline operation. Preserve the existing app, package identity, signing/updater compatibility, user data and working features. The user approves product behavior, design changes, permissions and major architectural tradeoffs. Do not infer that a requested feature is implemented merely because it was discussed in chat. Distinguish implemented, in-progress, proposed and verified states.

## Required project references
Before substantial work, read the relevant documents:
- `docs/UI_THEMING.md` for existing visual requirements.
- `docs/PRODUCT_REQUIREMENTS.md` for product behavior and approved design preferences.
- `docs/INTELLIGENCE_ROADMAP.md` for planned intelligence and modular-update phases.
- `README.md` and relevant code/workflows for actual current implementation.
Keep these documents synchronized with approved changes. When docs and code disagree, report the discrepancy rather than silently claiming either is complete.

## Product and UX requirements
- Preserve the existing Jarvis label, Arc Reactor visual/animation, theme palettes and current theme-dependent styling. Do not replace artwork or change ring motion without approval.
- Maintain a ChatGPT-inspired compact composer toolbar and combined Settings/Tools entry, but do not imply feature parity where not implemented.
- Settings must have organized, non-looping navigation. Appearance controls include themes, font size/color and chat surface darkness where supported. Intelligence settings and progress should be understandable and accessible.
- Preserve themed popup menus, diagnostics, embedded browser chrome, dialogs and keyboard-compatible layouts. Support narrow screens, accessibility and larger fonts.
- Treat the user's past preferences as specifications to confirm against code, not evidence that a feature is already shipped.

## Voice, assistant and intelligence
- Support reliable wake-word activation (user-preferred default: “Hey Jarvis”), background listening where Android allows it, clear listening state, voice selection, speech-to-text, and configurable introduction/wake responses.
- Preserve user-approved offline/hybrid behavior: local commands and supported models work without a network; online services enhance rather than block core functions.
- Voice personalization, downloadable/bundled voice models and improved transcription are goals; verify actual models, licenses, size, device compatibility and installation behavior before asserting availability.
- Design conversation-context retention with explicit session boundaries, bounded storage, privacy controls and migration tests. Do not pretend chat memory is working without end-to-end verification.
- Support in-app Intelligence Plan progress reporting based on real tracked milestones, not invented percentages.
- Use platform-approved Android permissions and foreground/background service patterns. Never promise unrestricted system access, permanent background operation, root, or accessibility privileges on all devices.

## Engineering and security
- Inspect related modules, tests and workflows before modifying code. Prefer small, reviewable changes over broad rewrites.
- Never hard-code API keys, secrets or user credentials into source or APKs. GitHub Actions secrets are not safe to embed in distributable clients; use an authenticated backend or user-provided device-secured credentials.
- Keep production and beta variants distinct, with explicit package IDs, signing/update strategy and distribution rules. Beta users must not inherit developer secrets.
- Preserve release signing compatibility and versioning. Never overwrite or publish a production APK without validating its signature and upgrade path.
- Require regression checks for wake word, voice selection, navigation, chat, updater, offline mode, and theme rendering on every relevant change.
- Run available Gradle/build/test/static checks and document exact commands and results. If Android SDK, device, network or secrets are unavailable, say so. Never call an untested artifact release-ready.
- Maintain GitHub Actions reproducibility, avoid unrelated changes, and use feature branches and pull requests for review.

## Research and communication
- Record important research findings with source URLs, retrieval dates, licensing considerations and decision status. Prefer primary technical documentation.
- Report what changed, tests passed/failed, known limitations, and exact next steps. Do not invent build progress, ETAs, download URLs or device test outcomes.
- Ask approval before deleting features, changing public UX behavior, introducing paid dependencies, or changing data handling/security boundaries.
