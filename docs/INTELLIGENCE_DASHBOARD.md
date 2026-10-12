# Intelligence dashboard

Open the gear menu → Intelligence, or Voice settings → Intelligence / Local Learning → Dashboard. The dedicated ThemedActivity shows real local inventory rather than development-plan percentages.

Sections: stored command entries and paused entries; intent examples; approved preferences and confirmed recent actions; available routine definitions; primary online AI configuration; device-reported internet validation. A configured key/endpoint is not a healthy-connection claim. Read-only refresh and review perform no AI request, routine execution or intent-learning write.

Management: paged learned-command review, exact-phrase removal with confirmation, preference removal/clear, routine review/forget, and the existing automatic_command_learning setting. The switch controls new automatic verified AI phrase learning; explicit preferences, user outcome confirmation and routine approval remain separate. Original settings controls remain available.

The optional Test primary AI connection button sends one static test prompt, with no conversation, local memory, learned phrases, attachments or location. Gemini has the same routing priority as normal chat. Gateway testing disables learning and coordinate inclusion. Only the exact expected reply counts as a confirmed response; this is not a test of device actions. Results are session-local and labelled Last test with provider/time. Closing/recreating the screen does not persist a connection-health claim.

The screen uses theme-owned wallpaper, opaque-backed panels, stroke/corners and Material buttons; global font size/color preferences apply. Content, long reviews and pickers scroll. Forgetting a preference can leave a stored preferred-app routine unavailable until it is restored. Conflicting/unsupported routine rows are excluded from available definitions and reported as unavailable stored entries.

Validation: pure regression tests cover separate counts, pauses, primary-provider selection, strict connection reply acceptance, diagnostic location omission and read-only routine inventory. Android build/unit and gateway checks run in CI; theme checker passes locally. No emulator/theme or phone visual review is claimed until performed. Existing emulator layout CI is disabled.

Phone check: open Intelligence from the gear menu; compare counts with chat's learning/memory/routine commands. Review entries without any device action. Test the configured AI explicitly; check the Last test result. Try the learning switch and targeted forget controls, then confirm inventory refresh. Check the active theme, large fonts, narrow/landscape display and scrollability. Volume, timer and preferred-app routines were user-reported successful through 3.5.355; the dashboard requires its own verification.
