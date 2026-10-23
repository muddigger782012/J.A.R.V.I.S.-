# Personal diagnostic repair bridge

This increment extends Developer's existing repository reader, patch engine,
provider adapter, complete-diff controls and isolated branch publisher. It
consumes Personal's existing Self Diagnostics review share. It adds no scanner,
general shell executor, automatic repair service or release authority.

## User flow

1. In Personal Self Diagnostics, select a finding and Review in Developer.
   Receiving the share only prefills a draft; it performs no network or write.
2. In Developer, open Tools, Show developer controls, then Personal Jarvis
   diagnostic repair review. Select the source branch, optionally specify up to
   three new Personal test paths, then explicitly read the diagnostic source.
3. The bounded GET reader pins the commit and verifies the tree, regular source
   and Git blob bytes. Stale claimed revisions and invalid line references fail.
   The imported diagnostic and source-inventory claim remain unverified.
4. Review and explicitly stage the exact source/scope in the existing coding
   controls. Generate a complete diff with the owner's selected provider/model.
   An API key is needed only for that separate, explicit provider request.
5. Review every change and use the existing exact-diff approval control. Only then
   can the publisher create a new isolated Personal repair branch. It checks
   preimages, rechecks the source head, verifies the whole tree and commit parent,
   and never replaces or updates an existing branch.
6. Open task branch for pull request review opens GitHub's comparison page.
   Explicitly create the PR there to run Personal's existing checks. The read-only
   status action selects Personal's workflow and exact task commit. No PR or
   workflow dispatch is automatic. Merge, publication and installation require
   separate owner decisions.

## Boundaries

Product is explicit and bound into Personal proposal fingerprints, provider
context, branch identity and audit. Developer remains the default and retains its
separate existing branch and path restrictions. Personal source refs are main,
the existing diagnostics branch and bounded Personal repair branches.

Personal runtime repairs initially permit existing ordinary Kotlin/Java logic in
the Personal package plus new matching tests. Existing tests cannot be modified.
Other applications, manifests, UI resources, build/workflow files, credentials,
permission/Accessibility/screen controllers, commercial/provider configuration
and approval/release/recovery controls are denied. Protected recommendations need
separately reviewed development, never an automatic allowlist expansion. The
new bridge controllers are themselves protected in both the engine and existing
candidate audit.

Shared prose is discarded. Only bounded fixed diagnostic labels, source
references, claimed revision and digest enter app-owned planning. Source and model
output remain untrusted. Changing task, scope, provider/model, reviewed inputs,
source or diff invalidates the corresponding context or approval. An uncertain
commit is not automatically retried. Recreation restores no Personal approval.

These validators are engineering safeguards, not a hostile-code sandbox or a
comprehensive semantic security audit. Human diff review and existing CI, signing,
backup, recovery, promotion and installation controls remain necessary.

## Verification and remaining work

Unit checks cover malformed, duplicate and oversized shares; stale revisions;
forged blobs; changed proposals and products; package/path boundaries; protected
controllers; preservation of existing tests; new tests; and product-specific
publisher, comparison URL and CI attribution. Publisher transports are modeled;
the production validator and whole-tree creation contract run without live keys.

Robolectric exercises real workbench widgets/listeners, explicit read/stage
actions, invalid inputs, stale callbacks, discard and destroyed activities.
It is an Android simulation, not installed-device evidence.

A dedicated disposable API 34 emulator suite performs actual public GitHub GETs
through Android, stages verified Personal source, confirms approvals remain
disabled, rejects protected shares before networking and checks recreation.
Its strict harness requires all three cases and retains instrumentation output.
It never calls a model, approves a diff, creates a live repair branch, uses a
microphone or installs/publishes a production release. Only disposable debug
app/test APKs are installed for that test.

The current development PR records exact candidate, build, lint and test evidence.
A live owner-reviewed provider, approved repair, Personal PR/build and signed
recovery exercise; signed cross-app delivery; and real-phone verification remain
separate work. This does not declare Phase 5/6 complete or inflate whole-phase
percentages from modeled transport results.
