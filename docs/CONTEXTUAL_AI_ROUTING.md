# Context-aware AI interpretation

Unresolved online requests now supply the configured Gemini provider or updated gateway with a bounded capability list, approved browser/maps/music labels, and the last user-confirmed timer duration/app label. These saved values are sent deliberately to interpret references. Package IDs are resolved locally and not included in this structured context. Existing conversation context may contain earlier memory summaries.

The AI can propose OPEN_PREFERRED_APP (slot browser/maps/music) or REPEAT_ACTION (kind timer/open_app). Strict schemas and confidence of at least 0.90 are required. Missing context never triggers an invented target. Generic references with several possible action kinds should prompt clarification.

The host binds a proposal to an actual saved action. Yes requests that action; only `it worked` enables alias storage. Cancellation, unsuccessful outcomes and failed writes do not create aliases. Android launch success remains a request, not proof of the visible result.

Learned contextual aliases retain a reference, not a frozen target. After a browser preference change, `launch my usual browser` resolves to the new browser offline. Clearing the underlying preference makes the reference unavailable. Existing command-memory deletion and conflict checks apply.

Unmatched `open ...` requests now reach online interpretation. Installed-app resolution remains unique and exact before a proposal executes. AI can also propose SET_APP_PREFERENCE with exactly slot (browser/maps/music) and app_name. The host binds one uniquely named installed app, then asks approval through its local preference handler. This changes JARVIS memory only, not Android system defaults. No AI reply can directly mutate memory.

Gateway users must deploy the updated backend to interpret the new context. Existing gateways can ignore the additional request field without affecting prior intents. Phone verification is separate from CI unit/build checks.

## Verified preference learning

A preference proposal may retain the original phrase. On yes, the host rechecks the installed package, commits the preference and reads it back. Only a matching readback permits alias storage, and automatic command learning must be enabled. Saved preference and learned phrase are separate outcomes: alias write failure or a phrase conflict does not undo an approved successful preference change. Cancellation, expiry, unrelated input and missing/ambiguous apps never train a phrase.

A preference-setting alias stores the explicit target slot/package/label. Offline reuse rechecks the app and asks yes/no again before changing the preference. It does not open the app or change Android defaults. Opening contextual aliases continue to resolve the current preference dynamically. Bare confirmations and result reports cannot become aliases.

Phone verification of these new preference proposals is pending separately from source/build checks.

## Word-hyphen variants in learned commands

Learned phrase matching treats word-internal ASCII hyphens, Unicode hyphens and nonbreaking hyphens between letters as spaces. Thus a saved `go to browser` phrase matches `go-to browser` offline without relearning. Existing stored phrases are compared with the same key; reads do not migrate or rewrite entries.

Numeric signs, standalone dashes, negations and changed words/targets remain distinct. Save and deletion use the same equivalence rule. If older entries collapse to different capabilities or parameters, lookup declines all ambiguous meanings, including across per-handler allowlists, rather than selecting the most frequent one. Conflict checks block replacing an existing meaning through a hyphen variation. Preference-setting reuse still asks approval.

## Local learning review and correction

`Show learned commands` lists saved command phrases and intent examples in pages of 12. Use `show learned commands page 2` for another page. The list reads actual stored parameters and current assistant memory; it does not execute actions or rewrite entries. Contextual references show their current resolved app/timer, or explain missing memory. Invalid, unsupported and ambiguous saved commands are marked unavailable. Intent examples are identified separately; storage alone is not presented as proof of a physical result.

The review is handled before learned aliases and is excluded from the fallback conversation history. It is not sent to the AI provider as a batch. Its controls are reserved from new learning. Existing per-request context and ordinary conversational messages retain their prior behavior.

`Forget learned command <exact phrase>` removes matching local aliases/examples. Straight or curly double quotes around the phrase are accepted, as is `remove learned command`. Word-hyphen matching applies to command aliases. Forgetting a phrase does not clear approved assistant preferences or last confirmed actions, and does not disable built-in commands. Previously cached cloud conversation context is separate; this is local learned-command deletion, not a request to erase all chat history.

## Failure feedback for learned timer/app replays

A replay of a learned timer/app phrase retains the saved meaning alongside its requested action until the immediately following outcome report, with the existing five-minute expiry. `It failed`, `it didn't work` or `it did not work` pauses that matching alias through a durable failure record. The expected capability and parameters must still match storage; a stale outcome cannot pause a changed meaning. Contextual aliases pause their semantic reference rather than the currently bound physical app/timer.

Paused phrases stay visible in `show learned commands` with their failure count, but are excluded from alias matching and recent-action migration. Offline retry explains that the phrase is paused. Online retry can obtain a fresh AI proposal with approval and outcome verification. A verified successful lesson for the same saved meaning clears the pause, subject to automatic learning being enabled and a successful write. A different meaning retains conflict protection: explicitly forget the old phrase before teaching a replacement.

Cancelling a new proposal, reporting failure for an unlearned/local built-in action, unrelated or expired outcomes, failed action requests and bare result reports do not pause aliases. A failure does not overwrite previous confirmed context or app preferences. This release covers learned timer/app replays; volume changes and preference saves retain their existing direct verification paths. Phone verification is separate from unit/build checks.
