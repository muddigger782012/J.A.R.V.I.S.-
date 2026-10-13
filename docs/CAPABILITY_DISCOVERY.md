# Local capability discovery and feature-request backlog

`show offline capabilities` / `what can you do offline` provides bounded examples of actual local learning handlers. It distinguishes device-readback volume verification from user-confirmed app/timer outcomes, internet-dependent AI/search and unavailable arbitrary feature generation/offline generative AI. Broad domains in the older architecture catalogue do not establish operational availability.

`request a capability to check my calendar before I leave` explicitly records inert feature-request text on this device. `show capability requests` lists five per page; `forget capability request` plus the exact description removes only that request. Requests are labelled requested/implementation not verified, never matched as learned commands, executed, installed, classified as definitely missing, or sent to AI context/history. The dashboard shows their count and latest three as text in an existing themed panel; no oversized buttons are added.

The private backlog is limited to 20 requests of 1–240 characters. Canonical duplicates do not replace the original timestamp or text. Full storage refuses new requests rather than evicting old ones. A failed write cannot claim recording success. Review is read-only. Discovery controls are reserved against aliases. Read-only discovery preserves an app/timer outcome check and its existing expiry; recording/deleting a request is a new turn that invalidates the outcome context as before.

This is the discovery and request-capture foundation for a capability builder. It does not implement requested features or generate/test/deploy new code. Future classification and implementation must be tied to the actual host handlers and build pipeline.

Phone check offline: show offline capabilities; request a capability to check my calendar before I leave; show capability requests; repeat the request and verify deduplication; review it under Settings → Intelligence; forget capability request check my calendar before I leave; verify removal. Saved learning should remain unchanged and no calendar/device action should run.

Prior phone evidence: timer proposal edit, saved 600-second short-break alias and offline replay were verified in 3.5.374. App-target correction 3.5.375 has automated checks but no explicit phone result yet. This discovery milestone is phone-pending.
