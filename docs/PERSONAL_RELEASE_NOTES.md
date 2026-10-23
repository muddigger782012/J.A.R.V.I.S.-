Personal Jarvis v3.6 — Intelligent Device Assistance:

- Explain screen-read timeouts, changed screens, lost access, cancellation and
  size limits with specific recovery guidance. Failed reads never revive approvals.
- Ask "screen read diagnostics" for the last attempt's outcome, stage, node count,
  timing and Android cache mode. Diagnostics inspect no screen and retain no text,
  labels or user values; protected reads also redact counts.
- Check cancellation and deadlines between child queries on wide screen trees,
  preserving existing capture limits, protected-screen rules and per-tap approval.
- Prefer available Android on-device recognition for Personal live chat as well as
  commands. Report the capture engine; allow one announced system-provider fallback
  for unavailable local models while keeping explicit on-device practice strict.
- Preserve explicit default-provider practice and its offline-preference hint.
  Reject late recognizer callbacks after cancellation, replacement or fallback;
  keep transcript confidence aligned when blank results are removed.
- Understand polite, explicit media-volume requests such as "make the music a
  little louder" and "turn the music down a little" as one verified local step.
- Utility menus use the Settings theme with persistent Back and Close controls.
  Android Back returns through visited utility panels or the previous activity;
  Close returns to Chat. Existing controls, actions and the Chat interface remain.
- Appearance keeps theme/font tab positions and unfinished edits while switching
  tabs, and restores scroll position after applying a theme. Navigation remains
  accessible while scrolling; contrast controls share one scrolling body.
- Read supported screens with Accessibility caching enabled or disabled without
  redundant child refreshes; retain complete-capture, deadline and security bounds.
- Identify the foreground app and supported visible controls with roles, sections,
  availability and checked state. Duplicate controls require an explicit choice.
- Review a bounded task, approve the plan, then confirm each numbered target before
  one tap. Delayed confirmations cannot approve a replacement target.
- Inspect the resulting screen after each accepted tap and verify the requested
  visible text before completing the task. Uncertain taps are never replayed.
- Pause and correct task intent while keeping unrelated conversational approvals
  separate. Resume with a fresh screen review.
- Cancel through voice/chat, Tools → Screen task, or the notification's Cancel action
  when notifications are allowed. Queued requests are invalidated immediately;
  already submitted Android actions cannot be undone.
- Deliver notification cancellation promptly and bind it to the original task, so a
  delayed old Cancel action cannot discard a replacement task or its notification.
- Recover one disappearing Accessibility tree with a fresh bounded screen read.
  Partial trees remain unavailable, protected screens stay blocked and actions
  are never retried automatically.
- Keep password, Android-marked sensitive, locked, Android system/permission/Settings and security windows
  protected. Accessibility enabling remains a manual Android flow.
- Continue a voice-driven task with one follow-up capture after each review. Interrupt
  speech with the microphone control; stale recognizer callbacks and confirmations
  from a replaced review are discarded. Silence returns to wake standby.
- Choose Gemini or your HTTPS gateway for complex conversation and enable or disable
  automatic provider fallback. Keep manual Gemini model selection and gateway server
  models. Supported local commands run before either provider; ask for AI routing status.
- Inspect Wi-Fi, Bluetooth, cellular data, VPN and the default network locally.
  Radio/VPN changes open Android settings for your approval; diagnostics never scan,
  transmit identifiers, grant permissions or toggle restricted settings.
- Discover the independent J.A.R.V.I.S. Connection Manager by its actual package and
  explicitly open its reviewed launcher. It keeps its own application and credentials;
  no undocumented remote-control interface or provider authentication is assumed.
- Preserve the HUD, selected theme/fonts, installed settings/data, offline features
  and Personal developer/provider/model controls.

This increment supports label-based taps and existing navigation/scroll controls.
It does not provide arbitrary typing, coordinate gestures or unattended cross-app
execution. Verified visible text is distinct from delivery, payment or other
app-side outcomes. Screen contents stay outside assistant/provider memory and
learning/audit datasets; existing visible chat-history settings still apply.

Release preparation verifies production signing, manifests, APKs, deterministic ZIP
and checksums automatically. The existing updater receives the same verified ARM64
APK. Phone/OEM/voice and installed-data upgrade acceptance remain separate checks.
