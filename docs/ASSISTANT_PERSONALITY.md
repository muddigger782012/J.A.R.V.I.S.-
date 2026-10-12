# JARVIS personality and phrase controls

Settings → Assistant voice → Personality and phrases adds a saved behavior profile:

- Personality on/off (on by default).
- Form of address, initially Sir; blank disables forms of address.
- Brief, balanced or detailed replies; balanced by default.
- Occasional dry wit and useful next-step suggestions, both enabled initially.
- Editable introduction and newline-separated rotating wake responses; blank phrases are silent.

Save personality and phrases is separate from Use this voice. Existing speech switches still apply. The introduction is requested when the user explicitly enables voice, not when the foreground service restarts automatically. If Android TTS has not initialized or a suitable voice is unavailable, the phrase is skipped and listening proceeds.

The profile is read from an atomic local file on each request, including the separate foreground voice process. It informs Gemini conversation, command interpretation and search-answer synthesis. The app sends validated profile fields and up to eight conversation turns to the fallback gateway. Gateway personality support requires deploying the updated backend; older gateways may ignore the new profile field.

Local greetings, thanks and identity responses use exact matching so greetings inside action requests cannot swallow those requests. Deterministic action results and learning confirmations retain their factual wording. Personality does not authorize actions, change structured lesson fields, fabricate live data or turn an attempted launch into a completed action.

Wake acknowledgments finish before command recognition starts. Cancelled or superseded speech callbacks cannot restart a stopped listener. Muted, empty or unavailable speech still advances to listening.

This is a style and local conversation implementation, not a new local language model. General offline reasoning, automatic room transcription, cross-process shared conversation memory, new proactive monitoring, voice-model packaging and full-duplex interruption are not added by this change. The selected voice and existing validated command-learning behavior are preserved.

Reference ideas: InterGenJLU/jarvis's persona and context architecture, ironicgitposting/Jarvice's turn-taking, and isair/jarvis's conversation continuity. Implementation is original; no third-party source code or voice-cloning model is copied.

Validation: Python gateway suite (15 tests), UI theme check, XML/control wiring and whitespace checks passed locally. Kotlin regression tests cover exact conversational matching, greeting variation, profile controls, schema constraints and wake-phrase rotation. Android compilation and Kotlin test execution require CI; audio and themed layout require a device check.

Device audition:
1. Choose Sir, balanced, dry wit on; save. Ask a conversational question online.
2. Choose blank address, brief, dry wit off; save. Verify the next reply reflects those controls.
3. Disconnect networking; test hello, thanks and an existing local device command.
4. Set two wake replies on separate lines, then wake twice. Verify reply rotation and command capture after speech.
5. Mute regular speech; confirm wake capture continues silently. Stop voice during an acknowledgment and confirm it stays stopped.
6. Re-enable voice to audition the introduction, then change it to blank and verify silent startup.
