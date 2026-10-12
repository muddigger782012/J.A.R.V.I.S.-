# Compact search presentation

Completed searches render as one themed assistant chat card: a small query label, readable answer, inline citations, copy control, compact source previews, expandable remaining sources and an embedded-browser search action. The temporary search-ready card is hidden once a matching result arrives in the same user turn; pending/unmatched cards are retained. History is not rewritten by rendering.

A bounded text-only Markdown subset renders headings and bold emphasis, normalizes list bullets and inline code, and displays link labels without loading model-generated HTML or URLs. Only numbered citations linked to validated retrieved evidence open sources. Source numbering keeps the original non-image evidence order, and source snippets are trimmed to 220 characters/three visible lines. Opening a source uses the app browser; video actions keep the existing player. Answers can be copied. The first three sources display initially; Show all / Show fewer exposes the remaining sources inside the same card.

Explicit image searches retain their image gallery, and explicit video searches retain video previews. Ordinary web results do not display incidental images or oversized web thumbnails. Stored payloads now record search kind, defaulting legacy mixed results to web and legacy all-image results to image. Fresh retrieval uses cache v5 so old mixed payloads do not misclassify newly requested media. Google grounding suggestions remain displayed unmodified when supplied.

All colors, panels, corners, buttons and font preferences follow the current theme. No new palette or fixed corner shape is introduced. Regression tests cover readable text/citation preservation, excerpt limits, search-ready collapse boundaries, media-kind persistence and legacy image decoding. Theme checker and Android CI validate source/build behavior; emulator layout CI remains disabled. Actual phone appearance and interaction checks are pending.

Phone check: repeat the downloadable voice-model search. Expect one completed chat card, bold names without raw asterisks, concise source previews and no unrelated image gallery. Tap citations/source cards, expand/collapse sources, copy the answer and open browser search. Check the active theme, large font and portrait/landscape scrolling; then repeat an explicit image/video search and confirm media remains available. The previous 3.5.362 search retrieval was user-confirmed working.

## Compact action refinement

Following the 3.5.363 phone screenshots, the full-width filled Material buttons were replaced with one quiet, transparent text-action row: Copy, Sources · count, and Web. Each action keeps a 48dp touch area, active-theme text/ripple feedback, screen-reader description and font preferences, without a filled panel or border. Weighted columns and two-line labels accommodate narrow screens and large text.

Web answers initially collapse all sources behind the count/disclosure control. Expanding reveals all concise source rows inside the same chat card; the excerpts now use two lines. Searches without an answer and explicit video searches start with sources visible so results/media remain discoverable. Citations still open their original source, and copy/browser actions preserve their behavior. Explicit image galleries remain available.

Local theme/whitespace checks and Android build checks validate implementation; actual phone presentation requires checking the same voice-model query, Sources disclosure, Copy/Web taps, the selected theme and large-font layout. Earlier presentation tests remain relevant; this change adds no new parsing or routing logic.

The final refinement uses an answer → compact Sources disclosure → small icon footer arrangement. Copy and browser actions use 20dp outline icons within transparent 48dp touch areas, with active-theme tint/ripple, tooltips and accessibility descriptions. The Sources disclosure uses compact text/count so its purpose remains clear. Provider instructions now request a direct useful lead, concise paragraphs/bullets and no scraped page/interface boilerplate; evidence and grounding validation remain mandatory.
