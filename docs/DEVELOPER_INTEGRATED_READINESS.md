# Integrated Assistant and Developer readiness candidate

This is an isolated integration candidate, not a published release or approval
to merge, preserve a backup, sign, publish or install. It combines the existing
tested increments while preserving their original commits and application
boundaries. Current production and recovery releases remain independent.

## Included source

| Increment | Immutable source | Review |
| --- | --- | --- |
| Discover immutable recovery evidence without granting promotion authority | `297eeb6a0ffb07df7dc714783e9163a30be8321e` | [Recovery discovery](https://github.com/muddigger782012/J.A.R.V.I.S.-/pull/251) |
| Prepare an exact recovery snapshot and require separate canonical approval to write it | `71795793ca14208968ba14333ca82c2cdde04b61` | [Approved backup](https://github.com/muddigger782012/J.A.R.V.I.S.-/pull/252) |
| Verify updater bytes and require fresh installation review | `1472bf5f39f09e6a1ce6a982996cbe10ead897ba` | [Verified updater](https://github.com/muddigger782012/J.A.R.V.I.S.-/pull/253) |
| Remove duplicate Accessibility refreshes and measure bounded capture latency | `42533f2568392bbfffe825338bf54cb09495bfb1` | [Screen reads](https://github.com/muddigger782012/J.A.R.V.I.S.-/pull/254) |

The base is canonical Developer source
`3ba01ca35b4dd6fd4d138200b32fc7b020cf992e`. Its existing Assistant/integration
sources match main `b59e42ea4f684d7014f0d29521d1c738adf06cb4`.
The four sets modify disjoint files. The CI-only follow-up below adjusts the
placement of one unchanged native test. A subsequent Assistant repair adds one
budgeted fresh capture for explicit read/list timeouts; its scope, retained
deadlines and evidence are described in `SCREEN_READ_RELIABILITY.md`.
Integration does not replace any file with an older implementation or grant an
AI response authority to execute actions.

## Aggregate native timeout found during integration

The first combined run retained its original failed evidence: preflight18 passed,
then71 of72 full-suite cases passed before the360-second aggregate timer killed
the final twelve-theme `VoiceProfileUiTest`. The earlier component suite took
352.633 seconds, including34.676 seconds for that profile test. This is evidence
of inadequate aggregate headroom, not a passing final test or a phone voice fix.

Keep every native test and assertion unchanged. The existing mandatory API34
compatibility job now runs the complete profile test with a separate60-second
bound, validates the actual SDK before executing, and retains its raw result.
The full voice invocation explicitly excludes only that class and still requires
all71 remaining cases inside the unchanged360 seconds; preflight18 and its
120-second bound remain. API26/34 compatibility jobs still require their original
four cases and all three Manager iterations. A failed or missing API34 profile
result fails that job, which is already a required publication gate. The original
90 executions are preserved as18+71+1 across the required jobs, without retries,
increased voice deadlines or changed production code.

## Combined acceptance gates

The component checks are historical evidence only. This candidate needs fresh
exact-source compilation, unit/lint checks, native UI and provider/IPC tests,
recovery audits and repeated screen reads. CI artifacts and the integration PR
record actual results; this document does not predeclare their success.

- Retain all Developer and Assistant regression case identities. Run all
  candidate/recovery, backup, artifact and snapshot approval contracts together.
- Compile the independent Developer debug app and native test APK, retain its
  guided four-tab navigation and legacy repair tests, and run the updater tests
  against actual public release bytes in a disposable emulator.
- Compile and test Assistant, run the existing voice/navigation/compatibility
  gates and all three required Manager read iterations on each supported CI API.
- Run the Assistant–Developer bridge tests, including incompatible identities
  and commercial approval-mode restrictions.
- Audit the actual public updater, immutable recovery discovery and read-only
  snapshot plan. Their reports cannot substitute for candidate promotion,
  source-change approval, installation approval or device acceptance.

## Authority and delivery boundaries

Task-branch push and PR runs must not execute production signing, the recovery
writer, source writes, update publication or phone installation. The recovery
writer remains a separately approved canonical manual operation. No previous
release is deleted, moved or overwritten by preparing this candidate.

Developer update downloads still require exact package/version/digest/signature
inspection and the existing installed signing identity. Installation requires
fresh review and Android confirmation. Backgrounding invalidates pending review.
An attempted or handed-off install is not reported as installed.

The current published Developer build still requires its exact verified recovery
snapshot before autonomous promotion can proceed. An older verified snapshot
does not satisfy that requirement. Building and testing this isolated candidate
does not bypass it.

The Samsung S26 Ultra is not connected to this environment. The owner tests only
published versions; debug artifacts are engineering evidence, not a request to
install them. No live Gemini test, physical voice fix, phone installation or
production release is established by an emulator check.
