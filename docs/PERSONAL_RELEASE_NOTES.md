Intelligent screen assistance foundation for Personal Jarvis:

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
- Keep password, locked, Android system/permission/Settings and security windows
  protected. Accessibility enabling remains a manual Android flow.
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
