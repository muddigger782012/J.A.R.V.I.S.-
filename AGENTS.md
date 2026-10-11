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
