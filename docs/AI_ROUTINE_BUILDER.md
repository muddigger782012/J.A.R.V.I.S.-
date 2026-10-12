# AI routine builder: existing capabilities

An online request such as “Create a routine called quiet time that lowers media volume” can produce a strict CREATE_ROUTINE proposal. The host accepts only phrase and comma-separated steps, high confidence, and 1–3 distinct existing handlers: volume_up, volume_down, app_update. Volume changes are one step. The updater opens Android's approval flow; a routine cannot silently install an APK.

JARVIS previews exact steps. The user must reply `confirm routine` on the next input, within two minutes. Approval is shared by chat/voice instances and consumed once. `cancel routine`, unrelated input, corruption, expiry or failed storage prevents saving. Saving never runs anything. The approved routine runs locally when its exact name is invoked, including offline.

Named routines compose existing capabilities. They do not generate code, add Android permissions or manufacture new handlers. Ordinary requests to adjust volume remain separate from requests to create a routine. Unsupported/missing steps require clarification. Gemini and the configured gateway receive the same schema.

Routine names cannot occupy built-in command phrases or learned phrases, including paused/conflicting learned commands. Conflicts are checked again on approval. An existing routine with different steps cannot be replaced silently: list routines, forget the old routine, then review a new proposal. The 50-routine limit rejects additions rather than evicting an old approval. Conflicting legacy rows cannot execute.

Manual multi-step `teach Jarvis that … means … and …` proposals use the same approval boundary. Volume execution reports actual verified changes; the updater reports only a requested launch. No routine execution is automatically added to intent-learning memory or recent timer/app context.

Phone check:
1. Online: `Create a routine called quiet time that lowers media volume`.
2. Check the preview; say `confirm routine`. Verify volume has not changed yet.
3. Offline: `quiet time`. Volume should decrease one step (unless already at minimum).
4. `list routines` shows its saved steps; `forget routine quiet time` removes it.

Regression tests cover schemas, approval across recreated instances, next-turn cancellation, expiry, storage failure, conflict protection, paused learned names and capacity. Live AI interpretation and phone execution require device testing.
