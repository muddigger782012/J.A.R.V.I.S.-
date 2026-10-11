# UI theme contract

The selected appearance theme owns the app's visual language. New features must inherit it rather than introduce a separate palette.

| Element | Theme source |
| --- | --- |
| Background | `jarvis_background`, `jarvis_bg_deep`, `jarvis_bg_end` |
| Panel | `jarvis_surface_glass_strong` over `jarvis_bg_deep` |
| Border | `jarvis_panel_stroke` or existing component's `jarvis_surface_stroke` |
| Text | `jarvis_on_dark`, `jarvis_on_dark_muted` |
| Accent and icons | `jarvis_primary`, `jarvis_secondary` |
| Corners | `jarvis_corner`; chat bubbles use `jarvis_chat_corner` |
| Inputs and buttons | Existing themed widget styles and backgrounds |

Use `?attr/jarvis_*` in XML and resolve attributes from the active themed context in Kotlin. Combine translucent panel colors with the theme's deep background when content must remain legible. Avoid hardcoded feature-specific colors or rectangular/rounded geometry that overrides a theme.

Extend `ThemedActivity` for new screens. Use `ThemedDialogBuilder(context)` for dialogs; the shared dialog overlay supplies surface, border, corners and action colors, while the builder applies `FontPreferences`. Existing dialog validation, Copy/Clear and dismissal handlers must remain intact. For custom popup windows, use the same themed panel treatment as the composer and Tools/Settings menus and apply font preferences to the content. Size menus to the available window and allow scrolling.

Monochrome vector artwork may use white/black as a tintable mask; its displayed tint must come from the theme. Palette definitions belong in `values/colors.xml` and `values/themes.xml`. Font-color picker swatches and semantic diagnostic imagery may have intentional fixed colors, but must be explicitly documented. System-owned windows are outside this contract.

## Validation

Run `python3 scripts/check_ui_theme.py`. It rejects new hardcoded UI colors and standard dialog builders outside the shared builder. Its baseline records existing styling debt; it is not permission to add more. Do not regenerate the baseline when a new violation appears.

Review all themes, including alternatives, with normal and large fonts, narrow screens, keyboard shown and long lists. Check text contrast, icon tint, borders, corners, scrolling and action handlers. Compilation alone does not verify appearance; state clearly when device/emulator review remains outstanding.
