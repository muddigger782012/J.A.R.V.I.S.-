# Universal assistant platform and commercial decision framework

Status: **product direction and prioritization guidance, not implemented features or verified market estimates**.

## Product thesis
Jarvis Assistant is the flagship product and shared intelligence layer. Mobile, desktop, web and home endpoints should access the same assistant services where technically and commercially practical. Jarvis Keyboard and other standalone apps should reinforce, not fragment, the flagship product. Do not promise equal capabilities across platforms.

## Commercial optimization
For each substantial feature, assess: reachable users; acquisition/conversion/retention impact; development effort; ongoing maintenance; inference/voice/cloud costs per active user; platform-policy and security risk; accessibility and offline behavior. Prefer measurable improvements to reach, reliability, engagement and gross margin over speculative breadth. Record assumptions and validate with pilots.

## Compatibility strategy
- Primary: Android app reliability and Google Play/direct distribution; Windows 10/11 and web access; iOS/iPadOS companion; Home Assistant/Matter integrations through supported interfaces.
- Secondary: macOS and supported Google Home/Alexa integrations. Do not claim replacement of Nest/Echo firmware or wake assistant is generally supported.
- Exploratory: Windows 7/8.1 secure reduced-feature access, wearables, TVs and dedicated home hardware. End-of-support operating systems must not force weaker backend security.
- Smart-home commands must have authorization, confirmations for sensitive actions, auditability and safe failure modes.

## Shared assistant design
Define portable contracts for user identity, conversation/context, AI routing, memory/sync, tool invocation, device capability discovery and permission enforcement. Use platform adapters for microphones, wake words, automation, notifications and local storage. Maintain per-platform capability matrix. Prefer an API/protocol-first strategy and incremental extraction from the current Android app; avoid premature rewrites.

## Monetization and unit economics
- Treat consumer ChatGPT subscriptions as development tools, not customer inference licenses. Use authenticated commercial API access with quotas, metering, abuse controls and per-user cost attribution.
- Evaluate free/local-first tier, paid cloud features, and optional household/device tiers only after measuring usage, support costs and willingness to pay.
- Measure acquisition, activation, retention, paid conversion, inference cost per active user, gross margin, crash-free sessions and support burden. Do not present speculative figures as forecasts.
- Avoid purchasing or manufacturing dedicated hardware before demand and margins justify inventory, warranty and support exposure.

## Execution order
1. Stabilize Android and secure current release/update paths.
2. Establish baseline metrics, portable contracts and a minimal authenticated backend.
3. Pilot web/Windows and Home Assistant integrations using existing hardware.
4. Add secure optional cross-device synchronization and iOS/macOS clients.
5. Evaluate proprietary smart speakers and dedicated hardware with evidence from pilots.

Every PR should document relevant platform impact, operating cost, security/privacy implications and user-facing compatibility.

## Owner-approved platform priority (2026-10-14)
1. **Android phones and tablets** are the primary application and release target.
2. **Google home appliances and ecosystem** are co-primary for reach: Google Home/Nest devices, supported Google Home automations and Matter/Home Assistant bridges. Research supported integrations first; do not claim Jarvis can replace Nest firmware, built-in wake words or proprietary assistants.
3. **Windows desktop** follows Android/Google, prioritizing Windows 10/11; Windows 7/8.1 remains a secure reduced-feature feasibility target.
4. Apple, web, other smart speakers and dedicated hardware remain secondary or enabling channels. Web may still be used early as a low-cost technical bridge when useful.

Allocate roadmap and engineering effort accordingly. Maintain one shared Jarvis intelligence and cross-device identity, and prioritize measurable reliability, user reach, unit economics and maintenance burden over unsupported parity promises.
