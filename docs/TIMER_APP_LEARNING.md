# Timer and app-launch learning

The device-verified media-volume learning path remains unchanged. Two additional
capabilities use explicit user outcome feedback because launching an Android
intent is not proof that a timer started or an app reached its intended screen.

## Flow

1. An unfamiliar request receives a high-confidence structured AI interpretation.
2. Timers require an explicit duration from 1 second to 24 hours. App names must
   resolve to exactly one visible installed launcher app; the host binds its
   package, rather than accepting a package or code supplied by AI.
3. JARVIS stores the original phrase and parameters and asks approval.
4. “Yes” requests the existing Android action locally. It does not save a lesson.
5. After checking the timer/app, the user says “it worked.” Only this outcome,
   enabled learning and successful persistence allow the original phrase to be
   saved. “It failed,” cancellation, expiry, or unrelated input discard it.
6. Exact aliases replay offline with their saved duration or app package. Replay
   reports that the action was requested; it does not claim automatic verification
   or train another success merely because an intent launched.

Parameters are persisted in the existing validated-command memory. Historical
volume/update aliases without parameters remain compatible. Re-teaching the same
phrase with different parameters is rejected until the existing alias is forgotten.
Approval and outcome text cannot be stored as command phrases.

Timers show Clock UI for result checking and declare Android's SET_ALARM permission.
No Accessibility permission or screen scraping is required for learning these actions.

## Validation

Run Android unit tests and assembleDebug. DeviceLearningSessionTest covers timer
and app proposal → approval → user outcome → memory recreation/offline lookup,
parameter conflicts, duration/target validation, cancellation, expiry and failed
storage. Existing volume-learning tests must also pass.

Phone/provider behavior is not verified by these tests. On-device checks:

- Ask “give me five minutes to finish this,” approve, check Clock, return to JARVIS
  and say “it worked.” Restart offline and repeat the exact phrase by text first.
- Ask “bring up Chrome,” approve, check that Chrome opened, return to JARVIS and
  say “it worked.” Restart offline and repeat the exact phrase by text first.
- Check that a failed action or “it failed” creates no learned alias. Keep volume
  above zero and verify that the previously learned volume phrase still works.

Feedback is explicitly user verification, not automatic Android outcome telemetry.
