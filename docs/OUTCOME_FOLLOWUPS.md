# Bounded outcome follow-ups

An app/timer approval or outcome check can be inspected with `what am I confirming`, `what am I approving`, `which action`, `what should I check`, or `what was the last request`. The reply names the exact action and original phrase. Reminders never execute or confirm it, do not add an unclear/failure journal entry, and retain the original five-minute expiry.

After an action is requested, `show saved commands`, `show assistant memory`, and learning-status queries may run locally without discarding the pending outcome. A bare `yes` at this stage requests explicit worked/failed feedback rather than confirming success or discarding the check. Before execution, normal unrelated-turn invalidation remains; read-only inspection is retained only for outcome checks, not approval proposals.

`Actually, it worked` and `Correction: it failed` are bounded explicit outcome reports. Negated failure remains unclear; trailing actions, historical reports and hypothetical statements do not verify anything. Correction wording only applies to a currently pending check; it does not retrospectively rewrite a completed/failed journal entry or restore an expired action. A new action or unrelated turn replaces/clears the pending context as before. No cloud AI is needed for this flow.

Phone check: offline, open Chrome; ask `what am I confirming`; show assistant memory; say `actually, it worked`; then `do that again` and approve. Check that Chrome is the confirmed action. Approval reminder check: explicitly teach a fresh timer alias, ask `what am I approving`, then approve/check/report success. Reminders must not run the target themselves.

3.5.371 saved-command synonym and maximum-volume handling were phone-verified before this change. This milestone remains phone-pending until tested.
