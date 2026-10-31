# Screen-read refresh and latency repair

An unchanged Assistant/Connection Manager voice acceptance test intermittently
returned a bounded screen-read BUSY result. The first failed report and an
unchanged-source successful retry are retained in the earlier updater evidence.
That observation alone does not establish the cause or a physical-device fix.

Source inspection found avoidable remote Accessibility calls. API 33+ already
supplies fresh root/child nodes after a successful cache clear or with caching
disabled, but capture refreshed the root again. The fallback refreshed its root
before window validation and then a second time during traversal.

The existing capture implementation now applies one explicit freshness policy:

- Successful Android cache clear or disabled caching: use the fresh platform
  nodes without another refresh call.
- Older API or unsuccessful cache clear: refresh the root before package/window
  validation, and refresh every other visited node once. A failed refresh still
  rejects the entire capture; only the existing one full recapture is allowed.

The 1,500 ms traversal limit, 3,000 ms provider deadline, 4-second outer wait,
node/depth/character limits, cancellation epochs, protected/password/sensitive
screen restrictions, final consistency check and action approval gates remain
unchanged. No partial tree or stale cached result is substituted to make a read
appear successful, and device actions are never retried by this repair.

## Bounded recovery of explicit observations

Later combined acceptance exposed a disabled-cache capture that spent1,531ms in
controls with only1ms queue wait. A same-source repeat passed, so the underlying
framework/scheduling delay remains unresolved. Removing duplicate refreshes
alone does not establish a universal timeout fix.

An explicit `READ` or `LIST` request may now discard a BUSY capture and make one
fresh capture if at least500ms remains in its original provider deadline. Task
commands, taps, confirmations, scrolls, app queries, diagnostics and unscoped
direct callers do not acquire this policy. This uses the same two-capture ceiling
as the existing changed-tree recapture: a second timeout/change never starts a
third capture. Cancellation, disconnection, reading limits, unknown exceptions
and insufficient remaining time do not trigger timeout recovery.

The provider deadline still starts before queueing and is never reset. Each
capture retains its original traversal limit and all access, lock, password,
protected-window, size and consistency checks. The first capture recycles its
nodes on failure; no partial tree is reused. Actual action execution still calls
the single-capture path and gains no retry. No Android caching setting is changed.

When two captures actually start, diagnostics additionally report the first
fixed failure reason and the total read duration. Existing phase/node figures
describe the final attempt, while total read time includes both attempts. A
failed/protected final attempt remains failed/protected. There is no screen text,
exception text or new persistent history in this receipt.

Unit coverage checks operation scope, budget consumption, cancellation, opt-in,
nonrecoverable errors and the shared two-capture limit. Native diagnostics
acceptance injects a helper timeout and then requires actual Accessibility
content, and checks that read-recovery scope does not leak to later commands.
The disposable fixture also delays one child-node callback by1,550ms with Android
caching disabled. The actual cross-process READ bridge must then return complete
fixture content, record a first BUSY capture plus a second capture, and perform
zero taps. Its cache state is restored. This fixture callback and its arming
method are absent from release/Beta APKs; there is no production fault-injection
command. Both deterministic injections are separate from naturally observed
framework delays and physical-device measurements.
Real uncached stress runs and Samsung physical acceptance remain necessary;
bounded recovery cannot guarantee that an unresponsive Android Binder call ends
within the soft deadline.

`screen read diagnostics` now distinguishes the measured provider queue wait
from capture access/root/window/control/verification times and records the
selected refresh policy and attempted explicit refresh count. The transient
record contains only bounded durations/counts and app-owned enums. It contains
no labels, values, packages, node references, hashes, exception text or audio.
Protected reads expose neither node nor refresh counts. Reports describe their
recorded attempt, not current screen content or an active capability guarantee.
Queue time is available for a request that reaches execution; a request cancelled
before executing does not fabricate a new capture report.

The existing native Connection Manager assertions remain hard gates. Its failure
now includes content-free diagnostics. API 26 checks that fallback refresh count
matches the examined nodes; API 34 verifies fresh reads with caching enabled and
disabled, including zero explicit refreshes on the disabled-cache path. The
required compatibility jobs first retain all four previous compatibility tests,
then run the same Manager case three times each against the same immutable
independent Manager source. Each iteration must pass; none is a retry that hides
another failed iteration. Existing readiness windows and test deadlines remain
unchanged. Accessibility settings changed by the native fixture are restored.

Unit tests cover required refresh failures, recapture/cancellation boundaries and
bounded diagnostic privacy. Native tests also prove queue metadata does not leak
to subsequent unscoped reads. Timing results from actual remote reads must be
reported separately from the native test's explicit synthetic queue-value check.

These changes reduce known redundant IPC and make future delays attributable.
Passing emulators does not prove that every BUSY result, a slow framework Binder
call or the Samsung S26 Ultra's physical-device voice behavior is fixed. No UI,
signing identity, update channel, installed settings or Developer/Beta approval
authority is changed. Merge, production publication and physical installation
remain separate authorized steps.
