# J.A.R.V.I.S. development roadmap

This roadmap starts after acceptance of the current Intelligence Plan, conversation-context and settings-navigation update. It proposes incremental releases; it does not certify an APK, change the existing plan's completion estimates, or authorize replacing features. Keep implementation, automated verification and physical-phone evidence separate for every milestone.

## Baseline and compatibility contract

The project currently has one Android Gradle module, `:app`. `MainActivity` owns much of the chat, tools and navigation UI; `HybridAssistantService` coordinates deterministic commands, cloud interpretation, confirmations, learning and context. Smaller engine, adapter, storage, voice and search classes already provide boundaries to extend. `VoiceForegroundService` also runs in `:wake`, so session and persistence changes must work across processes, not just activity instances. The optional `cloud-backend` gateway remains independently deployed.

Preserve these contracts throughout the roadmap:

- Original package `com.assistant.core`, existing permanent signing certificate and increasing update version codes. Beta remains `com.assistant.core.beta`, with its own data and the same release source.
- Existing preferences, Keystore-backed user credentials, projects, tasks, workflows, learning, routines, chat history and downloaded voice packs survive updates. Preserve file/preference keys unless a tested migration is necessary.
- Arc Reactor artwork, themes, alternatives, fonts, chat contrast, personality, voice profiles, selected voices, response controls, wake word, local commands and opt-in integrations retain their behavior. New screens follow [the theme contract](UI_THEMING.md) and existing navigation conventions.
- User-configured provider keys stay encrypted on the device. Provider credentials never enter source, build outputs, diagnostics or APKs.
- Model interpretation, conversation context and remembered facts cannot approve an action. Existing local confirmations, allowlists, target binding, expiry, outcome verification and learning controls remain authoritative.

The maintained [seven-workstream plan](intelligence-plan.json) and its packaged asset remain the progress source. Update implemented/remaining/evidence descriptions when work lands. Do not derive completion percentages from build numbers, test counts or this roadmap.

## Stage 0 — Close the current update and measure a baseline

Complete the current PR using existing implementations. Record its source SHA, workflow results, APK variant, package/version, signer verification and artifact SHA-256. Distinguish an unsigned PR compilation from a permanently signed installable update. Run the existing JVM, optimized release/Beta, theme, native voice and Android navigation checks applicable to the change; retain CI reports. Verify real-phone installation as an in-place update with data retained.

Phone acceptance covers new chat, reopening history, switching chats during a delayed response, typed/background-voice continuity, offline plan rendering, explicit plan refresh, Intelligence/Appearance Back paths, large text, every theme family, voice selection/preview/playback and wake/listen/speak cancellation. Confirm approvals from an old conversation cannot affect the new one. Check the original app and Beta separately where their packaging/setup differs. Record any unavailable checks instead of treating compilation as phone evidence.

Before optimizing, gather reproducible measurements on a named physical ARM64 device and an emulator, recording OS, build, voice preset, pack state, network and sample count:

| Area | First measurements |
| --- | --- |
| Interaction | Cold/warm launch, local command latency, provider response latency, chat/history switching and scroll responsiveness |
| Voice | Wake-to-listening, speech request to first queued frames, model initialization/synthesis time, cache hit rate and cancellation delay |
| Resources | UI and `:wake` memory, model-loaded memory, background CPU, idle/wake-session battery drain, crash/ANR observations |
| Storage/delivery | APK bytes by variant/ABI, installed model/cache/context bytes, peak free-space need during update and voice preparation |

Report latency distributions such as median and p95 where sample size supports them. First queued audio frames are not sound-at-speaker latency. Agree regression budgets from these measured baselines; no performance target or battery improvement is claimed yet. Keep measurements free of credentials and conversation contents.

**Exit criteria:** current update's applicable automated checks pass; signed artifact identity is verified; phone results and outstanding limitations are written down; the baseline protocol is reproducible. A failed required check remains a release blocker, not a completed milestone.

## Stage 1 — Establish data-safe boundaries

Extract one responsibility at a time behind existing entry points. Start with routing/request-session orchestration and provider request construction from `HybridAssistantService`, then chat/menu coordination from `MainActivity`. Reuse current engine, adapters, confirmation stores, repositories and views. Initially use packages and interfaces inside `:app`; introduce separate Gradle modules only when dependencies are clear and a measured build/test benefit justifies them. Do not move theme resources, redesign the Arc Reactor or replace the UI framework as part of extraction.

Suggested dependency direction: UI/voice entry points → request orchestration → local routing/provider interpretation → existing confirmed action handlers. Storage and provider adapters expose narrow interfaces; pure schemas, command normalization and routing policy remain Android-independent where practical. Document the public contracts and ownership in a short architecture note with each extraction. Migrate callers individually and keep existing behavior tests running.

Before changing any database schema, replace the destructive upgrade strategy with explicit additive migrations. `storage/Database.kt` currently uses `DB_VERSION = 1`; `onUpgrade` drops all seven tables and recreates them. This is a future upgrade hazard, not evidence that the current APK has triggered a migration. Do not bump the version as a bookkeeping change. For the first real schema change, test populated v1 → new-version migration in SQLiteOpenHelper's upgrade transaction, preserving IDs, relationships and records; test interruption/error rollback, reopen and older-file compatibility. New installations and upgraded installations must produce the same schema. Apply equivalent versioned, atomic migration discipline to JSON files and preferences without overwriting newer saved values.

**Exit criteria:** each extracted caller passes behavior/approval/context regressions; original package, persisted keys and theme output remain compatible; upgrade fixtures retain projects, tasks, workflows and audit records; no table-drop fallback can erase user data during an ordinary upgrade.

## Stage 2 — Improve reliability and performance from evidence

Unify typed, foreground-voice and `:wake` request lifecycle rules: immutable conversation identity/epoch, cancellation, bounded pending proposals and ownership of response delivery. Prefer a bounded executor or structured asynchronous scope over proliferating unmanaged threads, migrating one path at a time. Keep disk/network/model work off the UI thread. Before replacing persistence or threading, test process death, simultaneous chat/voice access and proposal consumption exactly once; an in-process lock alone does not coordinate two Android processes.

Define one provider contract for conversation input budgets, permitted capability context, schema validation, error classification and output proposals. Retain provider-specific protocol adapters and current offline/local routing priority. Add bounded response sizes and timeouts, explicit cancellation and clear 401/403, 429, 503 and malformed-response handling. Retry only bounded read-only operations where justified; never replay an action, approved routine or external assistant command automatically after an uncertain outcome. Recheck conversation identity and bound targets before accepting a late proposal.

Use recorded traces to prioritize optimizations: avoid repeated model loading, preserve the existing fixed-phrase PCM cache, reduce redundant context parsing, and improve history/media loading only where measurements identify delays. Maintain separate budgets for foreground responsiveness, wake-service memory and battery. Do not eagerly load a generative model or voice pack merely to open settings.

Keep the existing downloadable Original voices and bundled Beta pack. Measure archive, unpacked and peak-install sizes; evaluate resumable verified downloads, packaging/native-ABI improvements and shared per-process initialization incrementally. Any new pack delivery retains hash/size checks, license files, atomic activation, interrupted-install recovery and all existing presets. Do not shrink an APK by deleting voices or silently changing the selected engine. Preserve offline installed-pack speech and the explicit fallback behavior.

**Exit criteria:** outage/cancellation/process-recreation tests pass; late responses cannot deliver to another chat or create its proposals; uncertain outcomes cannot duplicate actions; optimized Android voice regression passes; repeated baseline measurements meet agreed budgets with no regression in phone wake, speech or offline use.

## Stage 3 — Extend intelligence within explicit memory and approval boundaries

Keep three different forms of memory visible in the design:

1. **Recent conversation context:** bounded exchanges for the current chat, including existing isolation and management-list exclusions. It supports follow-up interpretation; it is not a learned action or durable fact store.
2. **Verified operational memory:** approved app preferences, confirmed recent timer/app targets, validated aliases and approved routines. Its existing target, conflict, pause, deletion and verification rules govern local execution.
3. **Future semantic memory:** optional durable conversational facts or summaries. Design it separately, with explicit scope, provenance, correction, expiry and user review/forget controls before implementation. Define how deleting a fact, chat, summary or learned command affects each store. Do not silently turn transcripts into cross-chat facts or send a local memory inventory to providers.

Build a versioned evaluation corpus from synthetic/consented examples for follow-ups, ambiguous references, changed/uninstalled app targets, negation, quoted requests, multiple actions, stale confirmations and offline/provider parity. Score routing, clarification, inappropriate action proposals and verified outcomes separately; engineering-plan percentages are not intelligence accuracy. Include both typed and speech-transcription variants.

Expand one supported action family at a time with a strict schema, unique local target resolution, appropriate approval, truthful outcome evidence and explicit offline behavior. Keep model confidence separate from device verification. Broader alias generalization must decline conflicting meanings and preserve explicit learning controls. Multi-action requests first clarify intent; routine composition uses existing bounded handlers and never treats conversational agreement as an installed capability.

Offline generative AI is a separate feasibility milestone. Measure model/license suitability, download and installed size, RAM, cold start, sustained latency, thermal/battery cost and quality on supported phones before proposing a user-visible mode. An optional model must not impede existing deterministic offline commands, wake detection or installed voice packs when absent, cancelled or too large.

Arbitrary capability-code generation remains experimental. Any future sandbox needs defined isolation, static checks, reproducible evaluation, permissions and a reviewable deployment artifact. Existing routines/trials remain the production capability builder until that system has evidence. Downloaded plan JSON never installs code or grants approval.

**Exit criteria:** each added action passes adverse schema/approval/outcome tests and phone verification; evaluation results document regressions and unresolved cases; any semantic-memory prototype supports review/correction/deletion without weakening action authority; no new on-device model is advertised before device measurements.

## Stage 4 — Complete lifecycle management and update recovery

Build capability lifecycle UI on the existing inventory, request identity, result history and trial evidence: requested → assessed → proposed → approved → tested → available/paused. Show what is implemented, why an action is unavailable and whether evidence is Android readback, user feedback, automated testing or phone verification. Review screens are local management surfaces; lists remain excluded from provider context and cannot execute actions while browsing. Add lifecycle controls incrementally within existing themed menus and predictable Back paths.

Strengthen the current publishing path with source/artifact provenance, verified original/Beta identities, monotonically increasing versions, credential scans and existing permanent signer checks. Keep signing material in CI secrets; PR validation requires neither signing secrets nor provider keys. Separate in-app plan refresh from APK installation, capability approval and provider configuration.

Design recovery around Android's actual update constraints. A lower versionCode APK normally cannot be installed as an ordinary in-place downgrade, and an older binary may not understand a newer schema. Prefer a same-signer forward recovery release and feature disablement where possible. Before offering backup/restore or rollback, define migration compatibility, integrity and credential handling; Keystore keys cannot be assumed portable. Never require uninstalling the original app as a routine recovery step. Test interrupted download/install, insufficient space, corrupt artifacts, signer mismatch and restart with existing user data.

**Exit criteria:** lifecycle displays reflect real stored states without granting execution; installation failure preserves the working app/data; recovery is rehearsed with populated data and an existing voice pack; each installable APK has verified identity, signature and version. Device update success remains separate evidence from CI build success.

## Map to the existing seven workstreams

| Plan ID | Incremental next deliverable | Main acceptance evidence |
| --- | --- | --- |
| `router` | Shared request/session contracts; action-family and voice parity | Adverse routing corpus, stale callback/process checks, typed/voice phone cases |
| `settings` | Complete learning/capability controls in current themed categories | Persistence, all-theme/large-font navigation, phone voice calibration |
| `learning` | Broader verified lessons with conflict/pause/forget protection | Offline restart/replay, failed writes/outcomes, phone target verification |
| `builder` | More bounded handler composition and linked trial evidence | Save-without-execute, approval/expiry, preflight and per-step outcomes |
| `dashboard` | Lifecycle state and evidence review using existing inventory | Accurate local counts/state, offline review, no browsing side effects |
| `providers` | Shared context/proposal contract and outage handling; optional offline feasibility | Protocol parity and failure tests, measured device feasibility |
| `updates` | Provenance and data-preserving forward recovery | Permanent-signer/package/version checks and populated-device update rehearsal |

Each deliverable should be a focused PR with the concrete behavior change, preserved contracts, architectural note when needed, exact automated evidence and pending device checks. Advance through these gates as evidence arrives; do not promise dates, performance gains or completion percentages before they are supported.
