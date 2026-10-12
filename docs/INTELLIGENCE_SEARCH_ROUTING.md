# Download-link intelligence routing

Requests such as “Find me a downloadable voice models replicating jarvis from iron man” now produce the existing web-search card instead of an ungrounded general-chat response. Natural find/show/get/locate download-page and download-link forms are recognized. Query length remains bounded to 300 characters; explanatory, negated, local-downloads-folder and install requests are not reclassified.

The screenshot follow-up “You can't search for downloadable links available online?” reuses only the immediately preceding explicit search request in the assistant's recent conversation context. It does not scan older turns, assistant-generated text or saved device memory for topics. Without a previous explicit search topic, the host asks what to find and describes the public-link search capability. No follow-up implicitly downloads or installs a file.

Search results follow the existing MainActivity retrieval pipeline: Gemini grounded search first; Tavily and the configured retrieval fallbacks if unavailable. Successful retrieval yields cited evidence and source cards; failed providers yield search failure messages rather than a false claim that browsing is categorically unavailable. This patch changes routing, not provider availability or the set of installable voice engines.

Regression checks cover the screenshot wording, alternate request forms, single-turn topic recovery and negative/installation boundaries. Phone check online: repeat the first screenshot's download-link request and expect Web search ready followed by results or an explicit provider failure. Follow with the screenshot's question and check that the same topic is searched. Source cards must remain clickable. No actual file installation is expected.

The user reported named installed-app routine testing successful for 3.5.360. Exact media-volume percentage routines remain the next milestone; this routing repair takes priority after the screenshot report.
