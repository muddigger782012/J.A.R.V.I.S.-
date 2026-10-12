# Local saved-command review and explicit media levels

`show saved commands`, `list my saved commands page 2`, and the existing learned-command controls share the local read-only review route. They do not require Gemini or send the saved list to AI.

Explicit media levels (including `raise media volume 100%`, `turn volume to max`, and `turn volume all the way up`) use the existing percentage setter and device readback before reporting verification. These commands are recognized offline ahead of relative-volume alias replay. Negated, explanatory, other-stream and multi-action phrases are rejected by the bounded local grammar.

Older relative aliases for those exact-level phrases are not overwritten or promoted into verified percentage lessons. Memory matching rejects their relative meaning, new relative lessons for the same grammar cannot save, and review explains the local level override. The user can still forget the obsolete entry individually. Other relative aliases remain unchanged.

Phone evidence before this update: 3.5.368 percentage phrase is listed with the correct 25% meaning; 3.5.370 explicit timer teaching/replay passed. Screenshots exposed saved-command synonym routing and stale one-step maximum-volume aliases. This update requires a new phone check: offline `show saved commands`, then `turn volume to max`, verify Android media level, then `show learned commands`. Test a lower percentage afterward if preferred.

Validation: parser boundaries, legacy no-write replay protection, review text, reserved-control protection, Android unit/build checks and existing UI theme checks. No new UI controls.
