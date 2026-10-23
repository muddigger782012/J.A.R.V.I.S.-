# Self-diagnostics and authorized improvement acceptance

This increment continues issue #208 and the existing read-only scanner in the
Self Diagnostics pull request. It adds working source/log analysis and a Personal
review page. It does not implement an approved Personal source-repair executor.
The stable signed Personal release is separate from this unmerged development.

## Implemented scope

Run the existing tool on an explicitly authorized checkout:

```sh
python3 -I scripts/jarvis_self_diagnostics.py --root /authorized/checkout --json
```

Optional `--repository owner/name`, `--revision` (full commit), `--build-log` and
`--crash-log` arguments describe explicitly supplied inputs. Revision is a caller
claim, explicitly not independently verified. A deterministic inventory digest
covers the supported files actually inspected; neither digest is a signature or
an owner approval. Findings have stable references, file/line evidence, priority,
explanation, expected benefit, risk, estimated difficulty and confidence wording.
They are static review signals, not proven root causes or a comprehensive security
audit. Unknown source locations in logs remain unknown instead of being invented.

The engine detects large sources, TODO/FIXME and unimplemented-code markers,
identical parsed Python function bodies, potential embedded credentials and
shell-enabled process calls. Supplied logs produce fixed compiler/test/crash,
memory-exhaustion, unresponsive-app and static-analysis diagnoses. Compiler paths bind only to
known inspected relative files; raw log text, private absolute paths, source
snippets and credential values are never included. Python duplicates are exact
statement-body matches; Kotlin/Java semantic duplication and dependency analysis
remain future work.

Traversal uses directory-relative descriptors and does not follow symbolic links,
including during a file/directory replacement race. Generated trees are pruned
before descent. Limits cover visited entries, candidate files (including skipped
ones), depth, bytes, findings and a checked time budget. Oversized/unreadable or
partially inspected inputs are reported explicitly. In-flight filesystem I/O
cannot be forcibly interrupted by these checks. Unsupported platforms fail closed;
the current safe descriptor implementation targets POSIX development/CI hosts.
Isolated Python mode excludes repository-local import shadowing and PYTHONPATH.
No repository code, logs, build commands or discovered instructions are executed.

The new read-only workflow runs scanner safety tests and uploads only its bounded
JSON report. An optional owner-selected Actions run reads failed-job logs from this
repository using read-only permissions; it never changes workflow state or source.
Manual workflow dispatch becomes available when its definition is approved onto
the default branch. PR execution verifies the source-scan/report artifact path.
Crash-log analysis remains a local, explicit tool invocation; Android logs are not
automatically collected or uploaded.

## Personal interface and Developer contract

Settings → **Self Diagnostics & Improvements** inherits MenuActivity, theme,
font, persistent Back/Close and Android navigation. It exposes source analysis,
build/application-error instructions, prioritized findings, approval status, local
review history, Actions test results and existing recovery instructions. Chat is
unchanged. Beta neither offers the destination nor opens the page.

The user selects a bounded local JSON report through Android's document picker.
Imports are read on a worker, limited in size/depth/string lengths, cancellable
with a deadline and rejected after page destruction or replacement. The previous
report survives cancellation/rejection. Only validated references are persisted;
imported prose, unknown fields, raw logs and source text are discarded. The viewer
uses app-owned explanations, benefits, risks and effort. Reports claiming source
writes, merging, publication, installation or passing tests are rejected.
Imported evidence and claimed revisions are always labeled unverified.

Selecting a recommendation offers an explicit **Review in Developer** action.
The independent `com.jarvis.developer` app already exports a text/plain share
entry which prefills a project prompt without automatically executing it. The
handoff contains only validated references and app-owned wording and explicitly
grants no source-write, merge, publication or installation authority. Missing
Developer is reported without any device/source effect. No credentials, approval
tokens, patches or arbitrary imported instructions cross this boundary.

Inspection of the existing Developer self-development implementation confirms its
allowlist currently covers Developer's own source, not Personal. A real Personal
repair therefore needs a separately reviewed scope extension and source-bound
proposal/approval contract. This increment does not pretend a shared draft is an
approved repair, independently attest imported evidence, or add duplicate repair
machinery. Actual cross-app delivery with the installed signed Developer APK is
still a separate device check. Actual document-provider cancellation/timeout and
installed-companion delivery also require device checks. Recovery opens existing instructions only; it does
not install, deploy, downgrade, undo or claim a rollback.

## Verification

The initial increment passed 701 JVM cases across 109 suites, all three debug
architectures, instrumentation Kotlin and Beta manifest processing. The scanner
also diagnosed the actual local lint failure and bound its first finding to
inspected source. The owner then authorized repairs to those existing blockers;
see [Android lint and compatibility repairs](ANDROID_LINT_REPAIR_ACCEPTANCE.md)
for the changes, two narrowly documented special-permission exceptions and
additional API 26/34 security/compatibility gates. The current verified commit,
full-lint results and final CI evidence are recorded in
[the development PR](https://github.com/muddigger782012/J.A.R.V.I.S.-/pull/212). The native harness adds a real page/recreation/privacy case;
required counts become 15 strict preflight and 65 full optimized cases, retaining
the existing 120/360-second deadlines. Existing Settings row and utility matrices
include the new destination rather than dropping old assertions. UI smoke visits
the page through Settings and checks Back navigation. Source/static theme checks
remain separate from actual emulator/phone appearance.

## Scoped acceptance and next milestone

These denominators describe this self-diagnostics workstream only. They are not
measurements of the entire six-phase assistant or claims about real-phone quality.
A row counts as passed only when its named automated check has passed.

| Phase 5 workstream criterion | State |
| --- | --- |
| Bounded authorized source inspection and evidence | Implemented; safety tests |
| Prioritized recommendations with benefit/risk/effort | Implemented; engine/viewer tests |
| Supplied build/crash-log diagnosis and source binding | Implemented; controlled log tests |
| Themed Personal review with persistent navigation | Implemented; UI/native gates required |
| Approved Personal repair through Developer | Not implemented |
| Verified repair execution/recovery through runtime workflow | Not implemented |

| Phase 6 workstream criterion | State |
| --- | --- |
| Reproducible evidence references without secret/raw-content export | Implemented; tests |
| Supervised recommendation/review draft with no write authority | Implemented; tests |
| Immutable Personal proposal, exact owner approval and scope renewal | Not implemented |
| Approved isolated patch and test-result workflow for Personal | Not implemented |
| Separate executable merge/publication/installation approval gates | Not implemented in this new bridge |
| Verified recovery of a failed approved Personal repair | Not implemented |

After the UI gate passes, the scoped ratios are **4/6 (67%) for Phase 5** and
**2/6 (33%) for Phase 6**. They must not be reused as overall phase-completion
percentages. The next milestone is a source-bound Personal repair proposal in
Developer: exact revision, allowed paths, intended patch/tests, owner approve or
reject, invalidation on changed scope/head/diff, and an isolated development PR.
Merge, signing/publication and installation remain separate owner decisions.
