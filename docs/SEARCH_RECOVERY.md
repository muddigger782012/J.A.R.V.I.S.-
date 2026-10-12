# Embedded search recovery and AI-first routing

Gemini is attempted first using the existing configured Gemini key/model and the Google Search grounding tool. A primary answer is accepted only with real HTTPS grounding chunks and matching citation support from provider metadata. Plain text with invented source links or missing grounding is not published as a live-search answer. Provider Search Suggestions HTML/CSS is preserved in saved history and rendered in a separate WebView with scripts, file access and device bridges disabled.

Tavily is fallback when AI grounding fails, is unsupported or does not return usable results. For media searches the grounded AI answer/sources remain primary, while retrieval providers supply image/video assets absent from the primary response. Optional Brave retains its previous fallback role. Public Wikimedia Commons image search provides a final keyless image fallback, with thumbnails, file description links and attribution. Commons is a public-file collection, not a replacement for every web image/video catalogue. The custom gateway still synthesizes supplied fallback search evidence; live primary retrieval in this release uses Gemini.

Tavily HTTP failures and recoverable error envelopes, including keyless limits, are distinguished from genuine empty results. Error bodies, provider instructions and credentials are never displayed. Shared keyless limits remain a provider limitation; no unlimited-search guarantee is made.

The Search menu submits into the chat search pipeline. Public streaming availability questions matching the supported grammar are routed to source retrieval instead of ordinary static AI fallback. Answers must respect insufficient evidence and regional limitations; no private subscription catalogue access is implied.

Google search and its consent redirects remain in the embedded browser. Outbound source pages retain external-browser navigation. Main-frame connection errors are visible. Thumbnail requests use a descriptive user agent and restore successful recycled views.

Tests use ordinary truck/image queries and provider fixtures. Android unit/build and theme checks do not establish live provider availability or device visual behavior; both require the phone test.

Primary references: https://ai.google.dev/gemini-api/docs/generate-content/google-search ; https://docs.tavily.com/documentation/keyless ; https://www.mediawiki.org/wiki/API:Imageinfo
