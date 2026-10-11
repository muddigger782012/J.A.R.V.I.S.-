# Context-aware AI interpretation

Unresolved online requests now supply the configured Gemini provider or updated gateway with a bounded capability list, approved browser/maps/music labels, and the last user-confirmed timer duration/app label. These saved values are sent deliberately to interpret references. Package IDs are resolved locally and not included in this structured context. Existing conversation context may contain earlier memory summaries.

The AI can propose OPEN_PREFERRED_APP (slot browser/maps/music) or REPEAT_ACTION (kind timer/open_app). Strict schemas and confidence of at least 0.90 are required. Missing context never triggers an invented target. Generic references with several possible action kinds should prompt clarification.

The host binds a proposal to an actual saved action. Yes requests that action; only `it worked` enables alias storage. Cancellation, unsuccessful outcomes and failed writes do not create aliases. Android launch success remains a request, not proof of the visible result.

Learned contextual aliases retain a reference, not a frozen target. After a browser preference change, `launch my usual browser` resolves to the new browser offline. Clearing the underlying preference makes the reference unavailable. Existing command-memory deletion and conflict checks apply.

Unmatched `open ...` requests now reach online interpretation. Installed-app resolution remains unique and exact before a proposal executes. Preference-setting requests still use local approved handlers; AI-generated preference mutation is outside this release.

Gateway users must deploy the updated backend to interpret the new context. Existing gateways can ignore the additional request field without affecting prior intents. Phone verification is separate from CI unit/build checks.
