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

## Intelligent interactive decision interface (owner requirement)
- Present choices as context-appropriate interactive UI rather than static prose: radio choices, segmented selectors, sliders, toggles, structured comparisons, diagrams, code diffs, and previewable layout variations.
- Render project-aware recommendations with an explicit rationale, estimated impact, default choice, and an approval action. Provide an optional detail view for advanced variables without overwhelming beginners.
- Where a visual change is proposed, show a responsive phone/tablet preview using the existing Jarvis visual system. Preview state must reflect current selections; label mockups honestly until backed by running code.
- For technical decisions, use structured widgets appropriate to the data: dependency and permissions matrices, build/test status, diagnostics with actionable remedies, resource/performance tradeoffs, and rollback consequences.
- Keep user selections coherent across cards and previews. Save approved values to the project design specification and apply them to subsequent generated edits; preserve previously approved choices unless changed explicitly.
- Allow the user to accept the recommended configuration, customize individual variables, request alternatives, or defer a nonessential choice. Require explicit approval for consequential changes.
- Include accessibility support: screen reader labels, touch targets, keyboard/Back navigation, readable contrast, and reduced-motion support.
- UI generation must be deterministic from a validated schema; no arbitrary executable code or untrusted HTML from model output. Reject unsupported control types and invalid values, with safe text fallback.
- Tests must verify that changing an option updates dependent previews and approval payloads, saved choices restore correctly, and untrusted model output cannot trigger file writes or release actions.

## Approved Interactive Build Architect configuration (owner-selected)
These are the current project defaults, not suggestions:
- Consultation mode: **Collaborative**. Present useful alternatives at meaningful design/build milestones, avoiding unnecessary interruptions.
- Navigation: **Expandable drawer**. All screens must provide reliable Back/close navigation.
- Information density: **Balanced**.
- Interface scale: **100%**.
- Proactive recommendations: **Enabled**.
- UI: **Context-aware interactive controls and live previews** for development decisions; selection changes must update the preview and the eventual approved configuration.
- Theme: **Preserve the existing J.A.R.V.I.S. theme and all previously approved defaults**, except where the owner explicitly authorizes a change.

Persist these values in project-scoped configuration once the feature is implemented. Never treat this specification alone as evidence that runtime preferences are already saved or functioning.
