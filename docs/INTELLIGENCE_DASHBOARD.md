# Intelligence dashboard

Open the gear menu → Intelligence, or Voice settings → Intelligence / Local Learning → Dashboard. The dedicated ThemedActivity shows real local inventory rather than development-plan percentages.

Sections: stored command entries and paused entries; intent examples; approved preferences and confirmed recent actions; available routine definitions; primary online AI configuration; device-reported internet validation. A configured key/endpoint is not a healthy-connection claim. Read-only refresh and review perform no AI request, routine execution or intent-learning write.

Management: paged learned-command review, exact-phrase removal with confirmation, preference removal/clear, routine review/forget, and the existing automatic_command_learning setting. The switch controls new automatic verified AI phrase learning; explicit preferences, user outcome confirmation and routine approval remain separate. Original settings controls remain available.

The optional Test primary AI connection button sends one static test prompt, with no conversation, local memory, learned phrases, attachments or location. Gemini has the same routing priority as normal chat. Gateway testing disables learning and coordinate inclusion. Only the exact expected reply counts as a confirmed response; this is not a test of device actions. Results are session-local and labelled Last test with provider/time. Closing/recreating the screen does not persist a connection-health claim.

The screen uses theme-owned wallpaper, opaque-backed panels, stroke/corners and Material buttons; global font size/color preferences apply. Content, long reviews and pickers scroll. Forgetting a preference can leave a stored preferred-app routine unavailable until it is restored. Conflicting/unsupported routine rows are excluded from available definitions and reported as unavailable stored entries.

Validation: pure regression tests cover separate counts, pauses, primary-provider selection, strict connection reply acceptance, diagnostic location omission and read-only routine inventory. Android build/unit and gateway checks run in CI; theme checker passes locally. No emulator/theme or phone visual review is claimed until performed. Existing emulator layout CI is disabled.

Phone check: open Intelligence from the gear menu; compare counts with chat's learning/memory/routine commands. Review entries without any device action. Test the configured AI explicitly; check the Last test result. Try the learning switch and targeted forget controls, then confirm inventory refresh. Check the active theme, large fonts, narrow/landscape display and scrollability. Volume, timer and preferred-app routines were user-reported successful through 3.5.355; the dashboard requires its own verification.

## Result history

New tracked app/timer outcome feedback and confirmed AI media-volume lessons are recorded in a separate on-device journal (most recent 100 entries). Each entry shows original phrase, human-readable target, time, verification source and whether new learning/recent context was actually saved. User success reports are labelled separately from device-verified volume changes. Unclear feedback records no success/failure and retains the same pending outcome. Failed learned replays report whether the pause was saved. Bare feedback and cancelled action proposals do not create outcome entries. Routine final-target confirmation is not evidence of the entire routine's success. Existing memory is not retroactively converted into journal events.

Gear → Intelligence → Action and learning results offers paged newest-first review and confirmed journal clearing. Reading performs no AI request or action. The journal is never supplied to AI prompts or used for offline command matching; it contains no API credentials or app package bindings. Clearing it does not clear saved learning, preferences, routines or pending confirmations. Logging failure never changes action/learning decisions.

User phone evidence: the 3.5.357 primary Gemini connection test returned the expected reply. This verifies that diagnostic on the phone, not all dashboard/theme controls. Result-history phone check: open Chrome, return and say 'it worked', then inspect the original phrase, target and User confirmed success entry. Review while offline, restart the app and check persistence; clear history and verify saved memory still exists. Check active-theme large text and scrolling.

The user reported the 3.5.358 result-history phone test successful. Named installed-app routine definitions are displayed using app labels; their fixed approved target remains separate from a dynamic saved preference.
