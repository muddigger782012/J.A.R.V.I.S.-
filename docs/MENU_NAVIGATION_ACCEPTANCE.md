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

Required before merge/publication: actual Accessibility/conversation preflight,
the full optimized Android suite (53 cases, including every utility activity,
Appearance restoration and closing nested menus without losing the Chat draft), UI emulator Back/Close/history/menu tests, gateway/theme checks,
production signing and public updater byte verification. Real-phone wake quality,
installed-data upgrade and live configured-provider availability remain separate
from unit/emulator evidence.
