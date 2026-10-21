# Unified J.A.R.V.I.S. UI re-sort

Reference: current SettingsActivity and SettingsMenuViews. Preserve existing wallpaper, header, arc reactor, theme palettes and all action handlers.

## Content panel contrast
Increase alpha of the panel fill only by 0.05 (13/255), clamped to 255. Do not adjust wallpaper, outer screen, buttons, strokes or text. Implement as a reusable panel-only treatment; preserve theme color RGB and border tint.

## Layout
Use compact Settings-style rows with icons, subtitles, chevrons, and 48dp minimum targets. Group existing controls in expandable sections; do not remove any existing functionality. Keep scrolling and accessible labels. Apply to Projects, System, Device Tools, Terminal, Device Administration, Activity Log, and Permissions.

## HUD concept
Adapt subtle angular cyan edges and compact technical accents from the supplementary screenshot, without adopting its landscape layout or making every control heavily outlined.

## Safety and integration
Implement in this isolated branch, validate Android build and tests, and reconcile with Codex changes before merge. Do not publish until validated.

## Release verification (PR #175)

- CI `testDebugUnitTest assembleDebug`: passed; debug artifact named `layout-smoke-apk`.
- Gateway unit tests: passed.
- UI theme contract: passed.
- Voice-playback emulator regression: pending at last check.
- UI-layout job: skipped, so a real device or emulator screenshot review is required.
- Update contract: personal app package `com.assistant.core`; updater follows `main` and `artifacts/app-debug.apk`. Build baseline is `3.4.109` / versionCode `109`, with CI overrides. The workflow artifact is **not** the in-app update.
- Before publication: verify installed version and signing continuity; publish a newer compatible APK to the updater path; verify updater installation and menu visuals; preserve separation from beta package and update channel.
