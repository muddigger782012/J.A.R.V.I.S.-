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
