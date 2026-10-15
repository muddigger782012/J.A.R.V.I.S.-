# Jarvis companion experience principles

Status: **approved product vision, not an implemented claim**.

## North star
A single recognizable, personable AI companion accessible wherever the user goes: home, phone, vehicle, work, desktop, web and supported wearables. All endpoints share one Jarvis identity and continuity rather than appearing as unrelated assistants. Make interactions engaging and natural without implying the AI is human or has feelings.

## Experience requirements
1. **Continuity:** Resume opted-in conversations and relevant context across devices, with clear identity and per-device session handoff.
2. **Personality:** Consistent user-adjustable voice, phrasing, humor, responsiveness and introductions. Do not fabricate memories or imply human personhood.
3. **Conversation:** Support interruptions, follow-ups, topic changes, voice/text switching and reliable listening/speaking state.
4. **Accessibility:** Design for different screen sizes, assistive technology, low bandwidth, intermittent connectivity and platform-specific capability differences.
5. **Context with consent:** Use device, location, home and schedule context only when permissioned; offer understandable controls, memory review/deletion and privacy-aware defaults.
6. **Offline graceful degradation:** Basic commands and local knowledge work when offline; explain which cloud-dependent capabilities are unavailable.
7. **Proactivity:** Opt-in, configurable prompts and reminders; avoid constant unsolicited notifications or monitoring.
8. **Trust:** Be clear about actions taken, ask confirmation for sensitive actions, and never silently control security-critical devices.
9. **Cost discipline:** Reuse portable intelligence, choose local processing when practical, and meter cloud inference and sync costs.

## Acceptance scenarios (future)
- Start a conversation on an Android phone and resume the same approved context on Windows or web.
- Issue a home command from a supported room endpoint with appropriate authorization.
- Switch voice to text mid-conversation without losing context.
- Lose connectivity and continue supported local commands; synchronize safely when restored.
- Disable memory/sync/proactive behavior and verify the assistant honors the settings across endpoints.

Prioritize measured reliability, retention and user trust before broadening device support. Product integrations are conditional on OS and vendor policies.
