# J.A.R.V.I.S. Developer — Interactive Build Architect requirements

Status: Approved owner preferences; implementation pending. Scope: personal Developer application, not commercial Beta.

## Fixed defaults
- **Navigation:** expandable navigation drawer for major tools and project destinations. Every destination must provide a visible, working close/back path; Android system Back must also work.
- **Layout:** adaptive phone/tablet layouts with responsive widths, scrolling, safe insets, and preserved scroll position when changing a setting. Avoid clipping and overlapping controls.
- **Controls:** hybrid icon-and-label controls. Use concise labels for ambiguous or consequential actions; icon-only affordances require accessible descriptions. Preserve existing J.A.R.V.I.S. theme, arc-reactor branding, typography choices, colors, and approved chat appearance. Do not replace the chat UI without separate owner approval.

## Consultation during development
1. Inspect project requirements and existing design decisions before proposing changes.
2. At the point of highest leverage, surface a small set of materially different alternatives for menus, navigation, controls, screen layout, component reuse, permissions, offline/online dependencies, and build/test strategy.
3. Provide one recommended option with specific trade-offs: usability, accessibility, maintainability, performance, implementation effort, and cost of later revision.
4. Modes: Automatic (ask only for consequential decisions), Collaborative (offer choices at milestones), Manual (wait except for mandatory approvals). Default to Collaborative for the personal Developer app until owner changes it.
5. Save accepted decisions in a project-scoped, versioned design specification. Later work must respect them and highlight any proposed deviation before implementation.
6. Consultation must not interrupt trivial edits or silently change approved design. Show previews/diffs when feasible; never label a conceptual preview as a working screen.
7. Never allow design recommendations to bypass source review, protected-file restrictions, signing safeguards, build evidence, or explicit publication authorization.

## Required acceptance evidence before claiming implemented
- Automated checks for default preferences, persistence, responsive layouts, accessible labels, drawer close/back behavior, and scroll restoration.
- Demonstration: open an existing Android project, recommend alternatives before UI changes, obtain approval, record decisions, generate a source diff, apply only approved changes, run tests/build, and report genuine APK/build evidence.
- Verify the personal Developer and commercial Beta configurations remain isolated.
- Do not treat a green compile-only workflow as proof of the interactive feature.

## Owner-selected defaults
Navigation drawer; adaptive layout; hybrid controls; preserve current Jarvis theme.
