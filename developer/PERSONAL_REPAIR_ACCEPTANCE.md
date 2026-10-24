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
10. Explicitly save the current verified task commit as a private history bookmark.
    After restart, Load private repair history reads local metadata only. Choose
    an entry and explicitly recheck its build diagnostics through the existing
    bounded GET reader. That report never restores approved scope or enables a
    compiler follow-up; begin fresh source analysis for any later source change.

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
An empty selector notification with no selected file or follow-up review leaves
an unrelated reviewed diff and its explicit approval intact.

History retains at most ten product/task-branch/commit references, newest saved
first, in Developer's private no-backup directory. No source, diff, task prose,
provider settings, credentials, test results, proposal or approval is persisted.
The versioned/checksummed format detects corruption, not malicious alteration;
saved entries remain untrusted bookmarks, never evidence of prior authorization.
Reading history performs no network operation and the placeholder selection
requires an explicit choice. Rechecking validates the fresh report's exact
product/branch/commit and cannot populate the coding engine's trusted result or
follow-up state. History operations invalidate any newer diff approval and
serialize with active coding operations. Recreation restores no selection,
diagnosis, pending operation or approval. Explicit Clear affects local bookmarks
only and can recover a damaged regular metadata file. Links and non-regular files
are refused. The bounded no-follow atomic checkpoint I/O is shared with existing
task plans; their V1 byte format and revision contracts are unchanged.

These validators are engineering safeguards, not a hostile-code sandbox or a
comprehensive semantic security audit. Human diff review and existing CI, signing,
backup, recovery, promotion and installation controls remain necessary.

## Preview cancellation

Cancel source / proposal preview stops the current coding preview before a source
write is authorized. It signals immediately, closes active repository/provider
connections and response streams off Android's main thread, and checks the signal
before each source/provider phase and after responses. A provider request may
already have been sent and cannot be recalled. The current worker retains its
operation slot until it exits, so repeated cancellation cannot accumulate new
parallel preview requests. Existing request, byte and time budgets still apply;
there is no automatic retry, provider switch or budget refund.

Changed preview inputs and Activity destruction also signal cancellation. Pending
and late results cannot populate the review, re-enable approval or overwrite a
newer operation. A fresh completed preview still needs explicit exact-diff
approval. The control applies only to the source/proposal preview; approved commit
operations retain their existing result/uncertain-outcome verification flow.
Repair sessions record PREVIEW_CANCELLED while consuming the attempt. A claimed
write still records WRITE_UNCERTAIN even if a caller mislabels it as cancellation.

## Safe build-read failure diagnostics

The existing BuildFeedback reader now reports an app-owned phase (discovery,
workflow/job metadata, identity validation, logs/storage, or report interpretation)
and a bounded category. Known HTTP statuses, connection/stream errors, socket
timeouts, invalid formats, response locations and existing evidence/time limits
are distinguished. Unclassified exceptions remain unknown; no network cause is
guessed. Validation failures still reject the same product/source/run/attempt
identities before logs are accepted.

Only fixed enums and a valid HTTP status cross the failure boundary. Original
messages, causes, suppressed exceptions, response bodies, URLs, signed storage
locations and credentials are discarded. The UI reconstructs its explanation
from the typed fields and gives a specific read-only next step; it never renders
Throwable text. Unknown exceptions retain the existing generic fallback.

Task feedback, private history, bounded repair and supervised development views
use the same formatter. A remote-read failure does not suggest deleting local
history. Failed rereads clear prior evidence or baseline/candidate eligibility;
they create no attempt, source scope, retry or approval. Log-access/limit failures
still return incomplete reports with safe limitations. Existing successful-report
fingerprints remain unchanged. No API scope, transport retry, redirect allowance,
request/byte/deadline budget or production gate is expanded.

This adds diagnostic visibility for future failures. It does not establish the
cause of the earlier intermittent live-metadata failure that passed an unchanged
rerun. Repair inputs remain pinned while a read is active, and destroyed views
discard callbacks.

Saved history has an explicit **Cancel saved build read** control. It reuses the
existing in-memory cancellation signal and bounded GET reader; only the selected
history request receives that signal. Credential edits and Activity disposal also
cancel it. Connection and stream cleanup run off the UI thread. Checks before
and after transport/body/interpretation phases reject cancelled evidence,
including partially collected logs; cancellation is never an incomplete report
that could be mistaken for usable feedback. The busy slot remains held until
the worker exits. A new read needs an explicit action and fresh signal.
Local save/load/clear operations, commit/outcome verification and publisher
operations receive no cancellation signal from this control. Bookmarks and
attempt budgets are unchanged; no source scope, retry or approval is restored.
Successful report fingerprints, origin/redirect/credential checks and all
existing request, byte and deadline limits remain unchanged.

An active repair session can recover after a read failure: its failed feedback is
cleared until a fresh verified read, while its original attempt budget remains.
This does not stage another attempt or restore approval. Storage HTTP failures
recommend a fresh retained-log read; GitHub API access guidance stays specific to
the API response. Source-mismatched development feedback also replaces old output.

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
History checks cover bounds, duplicate retention, corruption, invalid product and
branch identities, private metadata contents, explicit clearing, linked paths,
atomic failures and concurrent store instances. Existing task-plan checkpoint
regressions also exercise the shared filesystem implementation. Widget checks
cover restart/load/selection/read, foreign reports, denied operations, destroyed
callbacks and approval isolation from saved references.

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
A sixth case uses the actual private filesystem and native history controls to
load an explicitly modeled saved reference, choose it, recreate the Activity and
verify that neither coding scope nor approval nor selection is restored. It makes
no provider, writer or build request.
A seventh case uses native preview/cancel controls with a queued modeled worker.
Cancellation prevents every repository/provider/writer request, releases controls
only when that worker exits, and restores no approval across recreation. It makes
no cloud or GitHub request.
An eighth case drives the actual repair widgets and production feedback client
through a modeled HTTP 403. It verifies phase/action visibility, an unread error
body, closed response, no retry or staged repair, and no restored approval after
recreation. It makes no actual network request.
A ninth case uses the actual native history controls and production feedback
client with an explicitly modeled blocked metadata stream. Cancel closes the
stream off the UI thread, keeps the busy slot until exit, discards the result,
preserves the private bookmark and restores no approval across recreation. It
makes no actual network/provider/writer request. JVM checks also cover cancelled
metadata, direct logs and credential-free retained storage, the redirect boundary,
credential edits, late successful callbacks, disposal, explicit rereads and
unchanged successful report identities for both products.
The strict harness requires all nine cases within the unchanged 180-second
deadline and retains instrumentation output. It never calls a live model,
creates a live repair branch, uses a microphone or installs/publishes a production
release. Only disposable debug app/test APKs are installed for that test.

The current development PR records exact candidate, build, lint and test evidence.
A live owner-reviewed provider, approved repair, Personal PR/build and signed
recovery exercise; signed cross-app delivery; and real-phone verification remain
separate work. This does not declare Phase 5/6 complete or inflate whole-phase
percentages from modeled transport results.
