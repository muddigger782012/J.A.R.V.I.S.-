# Prioritized Jarvis development backlog

Snapshot 2026-10-14. Verify live CI and PR status before starting; an open PR is not completed work.

| Priority | Task | Reference | Done when |
| --- | --- | --- | --- |
| P0 | Intelligence menus, conversation context, update checks | PR #75 | Applicable tests pass; device behavior separately checked |
| P0 | Restore reliable UI emulator checks | PR #78 | UI smoke enabled and repeatedly passing, failure diagnosed rather than bypassed |
| P1 | Reconcile Codex instructions | PR #76, PR #79, PR #80 | AGENTS.md and architecture documentation consistent |
| P1 | Commercial compatibility baseline | PR #79 | App IDs, signing, storage, API integrations, update feeds inventoried |
| P1 | Product boundary rules | PR #80 | New major features classified; Jarvis remains standalone assistant |
| P1 | Codex efficiency system | This branch | Workflow, issue template, testing policy and backlog reviewed |
| P1 | Secure commercial gateway plan | docs/COMMERCIAL_ARCHITECTURE.md | No new embedded API secrets; migration approved and tested before switching |
| P1 | Google Play distribution readiness | docs/PLAY_STORE_READINESS.md | Signed AAB pipeline, Play-safe updates and permissions, size checks and internal testing; preserve direct APK updates |\n| P1 | Cross-platform architecture feasibility | docs/CROSS_PLATFORM_ROADMAP.md | Shared protocol and capability matrix; Windows 10/11 proof of concept without Android regressions |\n| P2 | Windows 7/8.1 legacy feasibility | docs/CROSS_PLATFORM_ROADMAP.md | Confirm secure runtime, TLS and reduced feature set before committing to support |\n| P2 | Apple platform feasibility | docs/CROSS_PLATFORM_ROADMAP.md | Validate iOS/macOS capabilities and distribution policy constraints |\n| P1 | Universal assistant commercialization strategy | docs/UNIVERSAL_ASSISTANT_STRATEGY.md | Track reach, retention, per-user cost, platform capability and commercial value per major feature |\n| P2 | Smart-home interoperability pilot | docs/UNIVERSAL_ASSISTANT_STRATEGY.md | Supported Home Assistant/Matter integration with permissions and safe actions; Nest/Alexa feasibility separately verified |\n| P2 | Keyboard integration | jarvis-keyboard-updates branch | Private source located; preserve com.coastal.circuitkeyboard and existing updates |

## Operating rules
- Pick one approved, testable task at a time; avoid overlapping edits to the same subsystem.
- Keep separate PRs for unrelated changes. Check dependencies before starting.
- Record code changes, tests, blockers, and physical-device verification before marking done.
- Prioritize correctness, security and release reliability over expanding scope.
