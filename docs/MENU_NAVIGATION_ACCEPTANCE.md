# Utility menu acceptance

The current-main implementation replaces the floating exit overlay with themed,
persistent navigation outside utility content. Chat's layout, composer, rendering,
voice controls and provider routing are unchanged. Existing controls remain their
original View objects with their IDs and listeners.

| Surface | Back | Close |
| --- | --- | --- |
| Projects, Device tools/Shizuku, System/status, Terminal, Activity log, Permissions, Device administration | Previous visited utility panel, then Chat | Chat; clear the utility trail |
| Settings, Intelligence, Appearance, Themes, listening, assistant voice, voice profile, connections, live history | Previous Android activity | Chat; dismiss the nested menu activity stack |
| Tools/Support popup | Parent group/root or the underlying screen | Chat |
| AI settings dialog | Dismiss without saving | Dismiss without saving and return to Chat |

Android Back retains the platform's keyboard and modal handling before ordinary
page navigation. Existing Save, Cancel, permission, destructive-action and protected
screen policies remain in force. Closing menus never approves a device action.

Appearance uses one scrolling body below its fixed tabs. Theme and font tabs retain
their independent scroll positions and original controls, including unfinished font
edits while switching tabs. Applying a theme restores the selected tab and scroll
position after recreation. Contrast controls remain reachable in the same body.

An existing Connection Manager acceptance failure also blocked the baseline release.
API 33+ screen captures now walk the fresh hierarchy after clearing the service
cache, avoiding redundant per-child remote refresh requests. Disabled caching also
supplies fresh child nodes and avoids duplicate refreshes; older APIs retain their
refresh path. Acceptance temporarily disables caching and verifies a real read.
Window changes invalidate capture; all time, node, protected-content
and cancellation bounds remain. No partial tree or automatic action retry is added.
Utility roots keep stable state IDs, and the Intelligence dashboard restores its
scroll position after layout when refreshing or recreating. The acceptance
assertion no longer performs an extra screen capture just to format
its diagnostic message, including when the assertion succeeds.

Local verification: 672 JVM tests across 106 suites pass without failures, errors or
skips; debug APKs for all supported ABIs, instrumentation Kotlin and Beta manifest
processing pass. Release/archive regressions, Python syntax and UI theme checks pass.

Verified delivery: [Personal 3.6.515](https://github.com/muddigger782012/J.A.R.V.I.S.-/releases/tag/jarvis-3.6.515)
is public and available through the existing in-app updater.
[Download the updater APK](https://raw.githubusercontent.com/muddigger782012/J.A.R.V.I.S.-/main/artifacts/app-debug.apk).

The reviewed branch and merged source pass the actual Accessibility/conversation
preflight (7 cases) and full optimized Android suite (53 cases). This includes all
nine utility activities' persistent exits, independent Appearance positions and
unfinished edits, actual theme recreation, and closing nested menus back to the
same Chat and unsent draft. Settings rows wrap across all 12 themes at 150% app
font size. UI emulator tests pass Android Back and Close for every main utility
panel, panel history, popup parent/exit behavior, Default/Stark/Cybertron selection,
and the small-screen keyboard case.

Default/Stark/Cybertron screenshots were reviewed. Eight Chat widget bounds,
classes and descriptions match the published baseline, and Chat's layout resource
is unchanged. The uncached-read correction also passes the unchanged real
Connection Manager assertion with Accessibility caching disabled. It retains all
capture, protected-content and cancellation bounds.

Production verification passes all three architectures with the existing signing
identity, native runtime and wake assets. Automatic publication generated the
manifest, ARM64 ZIP and checksums without manual artifact preparation. The retained
receipt verifies the public release and updater. An independent download of the
exact default updater URL matches the manifest SHA-256 and pinned Git blob, Personal
package/version and verified prior production signer. Acceptance fixtures are
absent; production DEX includes menu Close/history/Appearance state and task-bound
notification cancellation.

Implementation and verification:

- [Complete themed utility navigation and preserve Appearance state](https://github.com/muddigger782012/J.A.R.V.I.S.-/pull/204)
- [Keep uncached Accessibility screen reads within existing bounds](https://github.com/muddigger782012/J.A.R.V.I.S.-/pull/205)
- [Merged-source unit, gateway, Accessibility, voice and UI checks](https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/39923987802)
- [Theme contract](https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/39923988011)
- [Production signing and APK metadata](https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/39923987790)
- [Automatic publication and retained receipt](https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/39925237566)

This completes the menu delivery milestone. Phone/OEM wake and deep-voice quality,
installed-data upgrade and live configured-provider availability remain separate
from unit/emulator evidence. Further voice/device improvements, live AI diagnostics,
broader supervised automation and independent companion-app development remain the
next priorities; this increment does not claim they are complete.
