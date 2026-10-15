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

## Definitive owner goals: personal and commercial (2026-10-14)

### Personal Jarvis: autonomous device operator
Prioritize the user's actual fleet: Android devices, Windows 10 and Windows 7 computers, and Google Nest appliances. Target the deepest **authorized and technically supported** automation available on each device: Android apps/actions and accessibility where permitted; Windows file/program/workflow control through local agents; smart-home routines through supported Google Home/Matter/Home Assistant paths. Windows 7 is end-of-support: isolate legacy agents and never weaken authentication/TLS or expose insecure remote control. Nest devices do not generally allow replacement of their native wake-word assistant. Device agents must enforce least privilege, per-device capabilities, visible action logs, revocable access and confirmation for destructive, financial or security-sensitive actions. Never promise unrestricted OS access.

### Commercial Jarvis: an indispensable companion
Make one recognizable companion available across rooms, offices, mobile devices and supported appliances. Prioritize effortless onboarding, natural conversation, consistent personality, opted-in continuity, family/office account separation, reliability and accessibility. Evaluate multi-room/home and office subscriptions only after validating demand and cloud/service margins. Start on existing hardware; consider branded speakers only after pilots prove retention and hardware economics.

### Product separation and sequencing
The personal edition may use advanced local automation agents and permissions unsuitable for general app stores. Commercial editions must meet platform and privacy policies. Both should share portable assistant intelligence, identity, conversation protocol and validated capabilities, not require a complete rewrite. Initial focus: stabilize Android; prototype supported Google/home interoperability; build Windows 10 agent; evaluate secure Windows 7 agent; validate commercial multi-room experience.

## Personal Jarvis as primary digital interface and supervised developer (2026-10-14)
Owner priority: Jarvis should become the default place to chat, search/research, manage authorized device tasks, and request software development across personal Android, Windows 10/7 and supported Google appliances.

Five integrated capability tracks:
1. Conversational AI with context, persona, voice/text continuity and transparent model/provider fallback.
2. Search/research with source attribution, rich media where licensed/supported, clear distinction between retrieved facts and generated answers.
3. Permissioned device operations through per-device agents, visible status, safe action confirmations and auditable logs.
4. Coding agent able to inspect repositories, draft changes, run sandboxed tests/builds, and prepare signed releases using existing CI; secrets must not be embedded in APKs.
5. Supervised self-improvement: detect issues and propose patches, but require explicit owner approval for privileged changes, merges, releases, installs and permission expansion. Preserve stable rollback channels and avoid uncontrolled recursive code modification.

Sequence: stabilize chat and search; unify assistant tool routing and context; establish secure device-agent contracts; add bounded code-agent workflows through GitHub CI; then cross-device orchestration and validated self-improvement loops. Track task success, latency, operating costs, regression rate and user trust. Do not claim features are implemented until tested.
