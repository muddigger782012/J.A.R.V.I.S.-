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
6. If a commit request is interrupted, explicitly use Verify interrupted commit
   outcome. It reads the deterministic task branch, pinned source/preimages,
   complete resulting tree, commit parent and proposal audit identity, then reads
   the branch again. Only an exact stable match restores the result for explicit
   CI/review; it never restores approval, retries a write or resumes a closed
   repair session. A 404 means not observed, including possibly inaccessible or
   deleted branches; it does not prove that previous writes were undone.
7. Open task branch for pull request review opens GitHub's comparison page.
   Explicitly create the PR there to run Personal's existing checks. The read-only
   status action selects Personal's workflow and exact task commit. No PR or
   workflow dispatch is automatic. Merge, publication and installation require
   separate owner decisions.
8. Explicitly read exact task build diagnostics to inspect Personal's own
   workflow, pinned head, attempt and complete job inventory. The existing GET
   reader supplies only fixed Personal error categories, normalized source
   locations and anonymous failed/skipped test references. Raw messages, source
   excerpts, log test names and supplied JUnit inventories are omitted. Missing,
   inaccessible or oversized logs remain explicitly incomplete; workflow status
   never becomes an attested test inventory or approval. No model request, retry
   or repair starts automatically.
9. For a completed failed Personal compilation, choose a reported production file
   inside that repair's exact changed scope. Explicitly read the selected failed
   source, inspect its verified review, then explicitly stage a follow-up for a
   NEW diff. The reader checks the task branch still points to the failed commit
   before fetching source bytes and reuses the existing blob/path/size checks.
   The review binds the parent proposal and CI report/run/attempt into a separate
   fingerprint. Generate a new diff and review/approve it explicitly; the prior
   approval cannot authorize this source or a new branch.

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
Outcome verification also invalidates any newer diff approval. It keeps the
interrupted proposal in memory only; after recreation, inspect the recorded task
branch manually and generate a fresh review for any separate writing attempt.
No unreferenced objects or branches are automatically deleted or rolled back.
Personal feedback has a separate product integrity domain. Default Developer
branch and historical fingerprint behavior remain unchanged. Personal feedback
cannot start/prepare a Developer repair or authorize Developer candidate
promotion, even if a caller supplies a success conclusion. A read invalidates any
newer diff approval; destroyed activities discard its callbacks.
Compiler follow-ups cover exactly one explicitly selected, previously changed
ordinary production file. Existing tests remain immutable; no test-only failure,
infrastructure/unknown failure, passing/running workflow or foreign path expands
the scope. Multiple file choices are explicit, and multiple line references to a
single file do not choose an arbitrary line. New commits, CI checks, feedback
rereads or changed selection discard the corresponding old review. Moved branches
need a new source analysis instead of silently switching to a newer revision.

These validators are engineering safeguards, not a hostile-code sandbox or a
comprehensive semantic security audit. Human diff review and existing CI, signing,
backup, recovery, promotion and installation controls remain necessary.

## Verification and remaining work

Unit checks cover malformed, duplicate and oversized shares; stale revisions;
forged blobs; changed proposals and products; package/path boundaries; protected
controllers; preservation of existing tests; new tests; and product-specific
publisher, comparison URL and CI attribution. Publisher transports are modeled;
the production validator and whole-tree creation contract run without live keys.
Personal CI tests cover explicit product/workflow/source/repository/job binding,
GET-only discovery, inaccessible logs, storage credential stripping, report
privacy, anonymous case references and rejection at Developer write/promotion
boundaries. No supplied Personal log inventory is treated as complete evidence.
Interrupted-response cases cover both products, source movement, missing or
inaccessible branches, changed preimages/modes, extra changes, wrong parents or
audit identity, truncated trees, changed ref readback and mutated proposals.
Every outcome check is GET-only, including failed or rejected checks.
Follow-up checks cover file choice, single-file scope, changed report identity,
new-diff approval, moved/truncated/forged source, immutable tests, and pinned
GET-only source retrieval. Prior static-review fingerprints retain their V1
behavior; the follow-up domain includes its build reference.

Robolectric exercises real workbench widgets/listeners, explicit read/stage
actions, invalid inputs, stale callbacks, discard and destroyed activities.
The full coding widgets also exercise preview, exact approval, interrupted
commit, outcome verification, a newer approval invalidated by that read, consumed
proposal reuse, missing/mismatched outcomes and health/token guards. The provider
and writer responses in these widget tests are explicitly simulated.
It is an Android simulation, not installed-device evidence.

A dedicated disposable API 34 emulator suite performs actual public GitHub GETs
through Android, stages verified Personal source, confirms approvals remain
disabled, rejects protected shares before networking and checks recreation.
Those three cases perform no provider call or diff approval. A fourth Android
case runs the complete review/approval/interrupted-response/recovery widget flow
with explicitly modeled provider and writer responses; it verifies that recovery
makes no second write, then requires an explicit read-only failure diagnosis and
keeps approval consumed. It also selects, reads and stages the failed source,
generates a second preview at the failed branch, and verifies that another write
still requires a fresh approval. Source responses in that case are modeled too.
A fifth case reads actual public metadata for an
immutable completed Personal workflow. Protected/expired logs may be unavailable;
it verifies the source and product without inventing complete test evidence.
Its internal
test injection is unavailable to production callers and intent extras.
The strict harness requires all five cases within the unchanged 180-second
deadline and retains instrumentation output. It never calls a live model,
creates a live repair branch, uses a microphone or installs/publishes a production
release. Only disposable debug app/test APKs are installed for that test.

The current development PR records exact candidate, build, lint and test evidence.
A live owner-reviewed provider, approved repair, Personal PR/build and signed
recovery exercise; signed cross-app delivery; and real-phone verification remain
separate work. This does not declare Phase 5/6 complete or inflate whole-phase
percentages from modeled transport results.
