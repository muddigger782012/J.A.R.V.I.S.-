# Persistent assistant preferences and recent-action context

JARVIS stores a small local record: preferred browser/maps/music app targets and
the last user-confirmed timer/app action. This memory file stores no conversational
transcript and is not separately injected into AI prompts. Preference commands and
memory summaries can still appear in the app's existing conversation context when
a subsequent request uses cloud AI.

## Local commands

- “Remember my preferred browser is Chrome” proposes a preference. “Yes” saves
  it only after the host resolves a unique installed launcher app. This is a
  JARVIS preference; it does not change Android's system-default applications.
- “Open my preferred browser” opens the retained package locally. Browser, maps
  and music slots are supported; use each app's exact launcher label.
- “Use the same timer again” repeats the saved timer duration.
- “Open the same app again” opens the last confirmed app package.
- “Use that same timer again” and “use the last confirmed timer” are supported
  local variants. “Open that same app again” is also local. “Open the same browser
  again” uses the saved browser preference rather than looking for an app literally
  named “the same browser again.”
- “Show assistant memory” lists retained preferences and confirmed actions.
- “Forget my preferred browser,” “forget the last timer,” and “forget the last app”
  remove individual entries.
- “Clear assistant memory” asks approval to clear these records on this device.

Recent action context is recorded only after “it worked” for timer/app requests.
Preferred-app launches, learned aliases, repeat actions and direct local timer/app
commands now establish the same outcome check. These existing local commands
update recent context after feedback; they do not need to become newly learned aliases.
Unconfirmed/failed requests do not replace it. Existing parameterized lessons from
the earlier user-verified learning flow are imported once on update, preserving
newer context. Clear/forget does not resurrect imported context. Learned command
phrases remain managed by the existing separate learned-command controls.

Offline follow-ups use bounded saved parameters and existing Android executors.
They report that an action was requested, not that its resulting screen/timer was
automatically verified. Missing memory, missing apps and disk-write failures yield
local clarification rather than a claim of remembering or successful execution.

## Verification

ContextualFollowupTest covers the exact online/offline screenshot phrases and
preferred-browser launch → outcome → persisted last-app context, plus failed launch,
failed outcome and storage failures. AssistantMemoryTest covers instance recreation, migration, no resurrection after
clear/forget, failed writes/corrupt records, command parsing and approval expiry.
Run all Android unit tests and assembleDebug, including existing timer/app and
volume-learning tests. Actual device/provider behavior remains a phone check.

On the phone: approve a preferred browser, inspect memory, restart offline and
open that preference. Repeat a previously confirmed timer using “use the same
timer again,” and repeat the last confirmed app. Forget/clear and confirm the
entries remain absent after restart. New unconfirmed or failed actions must not
replace a previously confirmed recent-action entry.
