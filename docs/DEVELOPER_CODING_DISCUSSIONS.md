# Project-scoped Home coding discussions

Home coding requests can use up to three earlier completed exchanges from the selected saved project and application target. For example, after discussing a voice repair, “What about the tests?” includes the earlier owner request and AI reply as bounded conversation data. Gemini and OpenAI receive the same context contract. Manual provider and model selection remain unchanged.

Each request collects repository source again. Earlier owner requests are bounded search hints for the existing read-only analyzer; AI suggestions are never used as source-search instructions. The resulting excerpts must still pass the existing commit, blob, allowlist, redaction and task-binding checks. A previous reply cannot establish that current source was inspected. If fresh source is unavailable, the reply panel reports that fact even when an earlier discussion is supplied.

## Bounds and privacy

- Keep at most three completed exchanges, with at most 800 UTF-8 bytes per owner request and 1,600 bytes per reply.
- The saved discussion is at most 10,000 serialized JSON bytes. JSON escaping counts toward this budget; older exchanges are removed when necessary. Truncation preserves Unicode code points.
- The complete outbound repository/discussion data remains within the existing 20,000-byte context budget, and the provider retains its existing 64,000-byte request limit. Under pressure, older discussion turns and then lower-ranked source files are omitted; the displayed source summary lists only the excerpts actually included.
- Reuse whole-text credential redaction before shortening and persistence, and repeat it using the current credentials before sending saved context. Do not persist provider keys or GitHub tokens in discussion records. Local project identity is omitted from provider context.
- Existing legacy reply records are retained locally but are not automatically migrated into outbound discussion context.

The local history uses the existing project preferences. Only a successful, current generation ticket can save another exchange. Failed, cancelled, interrupted or superseded requests add no exchange. Changing the application target clears that project's discussion. Separate projects never share discussion context. Saving offline does not invoke source or AI services.

## Forgetting and lifecycle

Home has a **Forget coding discussion** control. It removes the selected project's reusable discussion, and the next request starts fresh. The saved project, instructions and latest displayed reply remain available. This control is disabled during an active request; Cancel remains available. A reset from another window invalidates a pending ticket so a late completion cannot restore forgotten context. Saved discussion survives reopening the project or recreating the activity; malformed or mismatched discussion data is ignored.

## Authority boundary

Earlier requests and AI replies are a separate untrusted JSON data field, never provider system messages, current source evidence, approval or execution results. The text-only provider has no edit, shell, build, publishing or installation tools. Explicit patch review, exact approval, source revision verification, development jobs and release/install gates remain independent. The new discussion/store/workbench files remain protected by the existing patch path policy.

Unit and integration tests use instance-local source and provider fixtures. Native Android acceptance covers following up after tab navigation, recreation, forgetting history and unchanged approval controls. Those tests do not establish a live provider conversation or physical Samsung behavior. Owner device acceptance must use a published build.
