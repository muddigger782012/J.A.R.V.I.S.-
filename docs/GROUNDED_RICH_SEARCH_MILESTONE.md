# Grounded technical answers and rich search — next milestone

## Observed problem
The John Deere 450J fuel-tank conversation produced ungrounded chemical and machine-tilting advice, repeated timer offers, and no visible technical references. Search cards already support numbered citations, expandable sources, copy, images for explicit image queries, and video previews for explicit video queries. Ordinary assistant messages do not automatically inherit those affordances.

## Implementation phases
1. **Safety and answer quality (this branch):** Strengthen search-evidence prompt for equipment-specific verification, no invented specifications, no unsafe/impractical physical actions, relevant follow-ups, and structured procedures. This only applies when SearchAiContext.INSTRUCTIONS is used; audit ordinary assistant prompt separately.
2. **Shared answer pipeline:** Route general assistant questions that require current or model-specific facts through retrieval, then evidence-grounded synthesis. Do not claim retrieval happened unless it did. Keep offline responses clearly labeled as unverified when appropriate.
3. **Unified rich chat rendering:** Reuse ChatSearchResultsView formatting, citation links, source disclosure, and media components in regular assistant turns, without rewriting history or exposing untrusted HTML. Support embedded retrieved images when relevant to the user's question, not incidental thumbnails.
4. **Context and intent:** Preserve equipment model, year/variant, observed symptoms and prior recommendations in follow-ups. Prevent repetitive timer offers when the user is asking about repairs. Keep context bounded and user-controllable.
5. **Evaluation:** Side-by-side test on John Deere 450J fuel tank, fault-code troubleshooting, wiring pinouts, and live searches. Score factual grounding, actionable safety, relevant citations, visual clarity, and context retention.

## Release gates
- Unit tests for grounded prompt rules, citation-source matching, unsafe URL rejection, rich-text rendering, and context continuity.
- Android build and signed APK validation for standard Jarvis and Beta.
- On-device screenshot checks for scrolling, theme, fonts, source taps, galleries, and conversation history.
- Do not alter package IDs, signing configuration, API-secret behavior, updater paths, or release channels.
- Keep development on a feature branch until tests and device review pass.

## Important limitation
Changing SearchAiContext instructions does not guarantee ordinary chat responses improve; their prompt/routing must be located and updated in a separate reviewed change.
