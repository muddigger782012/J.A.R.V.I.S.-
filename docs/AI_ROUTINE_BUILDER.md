# AI routine builder: existing capabilities

An online request such as “Create a routine called quiet time that lowers media volume” can produce a strict CREATE_ROUTINE proposal. The host accepts only phrase and comma-separated steps, high confidence, and 1–3 distinct existing handlers: volume_up, volume_down, app_update, timer:SECONDS, preferred:SLOT. Volume changes are one step. Timer duration must be explicit, 1..86400 seconds, with no leading zeros. At most one timer or preferred-app step is allowed, last, and neither can share a routine with the updater. Preferred slots are browser, maps and music, and must already be saved and launchable. The updater opens Android's approval flow; a routine cannot silently install an APK.

JARVIS previews exact steps. The user must reply `confirm routine` on the next input, within two minutes. Approval is shared by chat/voice instances and consumed once. `cancel routine`, unrelated input, corruption, expiry or failed storage prevents saving. Saving never runs anything. The approved routine runs locally when its exact name is invoked, including offline.

Named routines compose existing capabilities. They do not generate code, add Android permissions or manufacture new handlers. Ordinary requests to adjust volume remain separate from requests to create a routine. Unsupported/missing steps require clarification. Gemini and the configured gateway receive the same schema.

Routine names cannot occupy built-in command phrases or learned phrases, including paused/conflicting learned commands. Conflicts are checked again on approval. An existing routine with different steps cannot be replaced silently: list routines, forget the old routine, then review a new proposal. The 50-routine limit rejects additions rather than evicting an old approval. Conflicting legacy rows cannot execute.

Manual multi-step `teach Jarvis that … means … and …` proposals use the same approval boundary. Volume execution reports actual verified changes; the updater reports only a requested launch. No routine execution is automatically added to intent-learning memory. A requested timer has a local outcome check; only a successful user report records that physical timer/app in recent-action memory. Its routine name is not learned as a single timer alias, which would skip other approved steps. Failures stop subsequent steps and leave the approved definition intact. Failed outcome tracking is reported without claiming verification or retrying the timer.

Phone check:
1. Online: `Create a routine called quiet time that lowers media volume`.
2. Check the preview; say `confirm routine`. Verify volume has not changed yet.
3. Offline: `quiet time`. Volume should decrease one step (unless already at minimum).
4. `list routines` shows its saved steps; `forget routine quiet time` removes it.

Regression tests cover schemas, approval across recreated instances, next-turn cancellation, expiry, storage failure, conflict protection, paused learned names and capacity. Live AI interpretation and phone execution require device testing.


Timer composition phone check: online, “Create a routine called focus time that lowers volume then starts a five-minute timer.” Review and confirm; saving must not run anything. Offline, invoke “focus time” with media volume above minimum. Expect one volume decrease and a requested 300-second timer. Check Clock and say “it works”; “show assistant memory” should show that confirmed timer. “Do that again” should propose repeating the timer. Do not treat a timer launch intent as proof the timer started.

User reported success for the first volume-routine stage in 3.5.353. Timer composition is a new milestone; phone verification is pending.


## Preferred-app composition

A routine may end with `preferred:browser`, `preferred:maps` or `preferred:music`. Preview includes the current saved app label. Creation and approval check that the slot is saved and launchable. Execution resolves the current approved package once before any side effect; missing/cleared/unlaunchable preferences block all steps, including preceding volume changes. Preference changes during execution cannot switch the snapshotted app. The stored routine contains the slot reference, not a frozen app or package.

An app-launch request is not a verified result. Its local pending outcome contains the actual requested app; only `it works`/`it worked` records that app in recent context. The routine name is never learned as a single-app alias. Timer, preferred app and updater cannot share one routine; broader sequential UI workflows remain future work.

Phone check: create “browse mode” to lower volume then open the preferred browser, review, confirm. Offline invoke it with volume above minimum, check the launched app, and report `it works`. Change the browser preference through its separate approval flow and repeat: the routine should use the newly approved app. Clearing preferences should prevent all routine steps.

User reported success for timer composition in 3.5.354. Preferred-app composition requires its own phone verification.

## Named installed-app composition

AI drafts may now propose a final `open:APP_NAME` using the exact user-requested launcher label. For example: “Create a routine called chrome time that lowers media volume then opens Chrome.” The host resolves one uniquely named launcher app, converts it to a package/label binding before review, and accepts only the same strict routine approval flow. AI-supplied package bindings are rejected; unsupported/ambiguous/uninstalled labels, multiple target steps, non-final targets and updater combinations fail without executing or saving. App names containing commas cannot be represented by this draft schema and require clarification. Stored labels are canonically URL-encoded to preserve separators and Unicode safely.

Unlike preferred-app routines, named-app routines keep the approved package. Changing the browser preference does not redirect them. Creation and approval check launchability; execution preflights the bound package before any side effect. Unavailable apps block all steps and are not silently replaced with another app. Saving never launches anything. Offline invocation requests the bound app through the existing outcome tracking; only user-confirmed success records recent context. The routine name remains a routine, not a single-app learned alias. Dashboard and chat routine previews show human-readable app labels.

Regression tests cover local binding, approval/restart persistence, rejection of AI packages and ambiguous labels, strict target order, encoded label validation, missing-app preflight and independence from changing preferences. Existing timer/preferred-app tests retain their behavior. Phone check: online create the chrome time routine, review and `confirm routine`; verify saving did not launch Chrome. Offline say `chrome time` with media volume above minimum. Check Chrome opened, return and say `it worked`; confirm result history and recent-action memory show Chrome.

Phone evidence through 3.5.358: volume, timer and preferred-app routine stages were user-reported successful; Gemini diagnostic and new result-history check also succeeded. Named installed-app composition needs its own phone test.

## Exact media-volume levels

AI-built routines may include `volume:PERCENT`, an explicit integer from 0 to 100 without leading zeros. For example: “Create a routine called low browse that sets media volume to 25 percent then opens Chrome.” The proposal shows the percentage and the named app before the existing `confirm routine` approval. Saving never runs anything. Only one absolute volume step is allowed, and it cannot be mixed with relative volume-up/down steps or another percentage. Timer/app targets remain limited to one final step. Existing approved relative-volume routines keep their behavior.

Android exposes discrete media-volume indices. The host rounds the requested percentage to the nearest supported index, sets only STREAM_MUSIC, and verifies readback under the same volume lock used for relative adjustments. An already satisfied level is verified without another write. Invalid parameters, blocked writes, exceptions or incorrect readback fail the step and prevent subsequent actions. App availability is still checked before any side effect. A volume level is not proof of audio playback or of a later app's success.

The percentage step's verified/not-verified result is recorded in local result history, without turning the routine into a single-action alias. A final app/timer keeps its separate user-outcome confirmation before recent context is saved. This release extends routine composition; general one-off percentage learning is not introduced.

Tests cover discrete rounding, bounds/overflow, already-satisfied targets, denied/ineffective writes, durable approval, conflicting volume steps, stopping on failed verification and missing-app preflight. Android/gateway CI and source checks are separate from phone tests. Phone check online: create low browse, review and confirm; verify saving ran nothing. Offline invoke low browse, check media volume near 25% and Chrome launching. Return and say `it worked`, then inspect result history. Invoke again: an already satisfied volume level should not block Chrome. Percent 0 and 100 are supported but need their own device testing.

User phone evidence: the compact search layout in 3.5.364 was described as much better, and named-app routine testing through 3.5.360 succeeded. Exact-volume composition requires its own phone verification.
