# J.A.R.V.I.S. Connection Manager — Implementation Contract

Status: Design approved; implementation pending.
Branch: feature/connection-manager
Reference: approved HUD screenshot from project conversation, October 2026.

## Visual acceptance criteria
Reproduce the approved screenshot faithfully at its reference phone aspect ratio. Black/navy sci-fi circuit-board wallpaper, electric cyan fine-line borders and glow, arc-reactor logo, J.A.R.V.I.S. CONNECTION MANAGER header, two circular top-right controls. Central luminous blue Earth with concentric animated HUD rings. Network Status left, Connection Health right. Six service status cards in a two-column grid: Internet, Web Search, Gemini AI, AI Fallback, GitHub Updates, Local AI. Four Quick Actions: Run Full Diagnostics, Test All Connections, Repair Connections, View Logs. Recent Activity timestamped log. Four-tab bottom navigation: Overview, Diagnostics, Providers, Settings. Preserve density, alignment, shape, hierarchy, and icon styling. Do not substitute a generic Material dashboard. Handle Android insets and small screens with scroll, avoiding clipping.

## Truthfulness and functional behavior
All example figures and green statuses in the reference are illustrative only. Never display fabricated latency, speeds, public IP, authenticated status, or service health. Use UNKNOWN / NOT TESTED until measured. HTTPS 204 confirms only the tested endpoint, not Gemini or search. Test each service independently and distinguish unavailable, unconfigured, authentication failure, timed out, degraded, and healthy. Show last-tested timestamps. Do not claim Android network repair privileges the app lacks.

## Architecture
Implement standalone Android app module with its own applicationId and update channel. Share reusable Kotlin connection diagnostics library with main Jarvis app. Preserve existing developer configuration and main app functionality. Use Android ConnectivityManager for active network/capabilities, safe DNS and HTTPS probes with bounded timeouts, explicit provider-specific API tests without exposing secrets, GitHub update manifest check, and local model availability checks. Avoid continuous background polling and unnecessary battery use. Use encrypted storage for any provider credentials, never log secrets. IPC integration must be explicit, permission-protected, versioned, and optional so Jarvis remains functional when companion app is absent.

## Delivery phases
1. Inspect existing Gradle structure, AGENTS.md, theming assets, updater and CI workflows.
2. Scaffold app and shared diagnostics module; implement screenshot-faithful Overview UI.
3. Wire tests, logs, settings, provider diagnostics and recoverable errors.
4. Add optional secure main-app integration and independent update channel.
5. Add Android unit tests, instrumentation screenshot comparison, CI debug APK build and release checks.

## Definition of done
APK builds successfully in CI; installation succeeds; reference screenshot compared at matching viewport; all controls are functional; connection states are measured rather than mocked; no regression in main Jarvis app; download and update channel tested before claiming published.
