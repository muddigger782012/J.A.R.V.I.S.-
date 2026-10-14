# Beta: same code, user-configured Gemini

The release and Beta APKs are compiled from the same source revision in the existing main-branch workflow. Beta retains all J.A.R.V.I.S. features, themes, voices, personality, and the Hey Jarvis wake word. Android requires a separate application identity (com.assistant.core.beta) so the app named Beta can install alongside the original. Both apps use the existing permanent signing key.

Run **Build Jarvis APK** on main; every subsequent main update builds both apps automatically when the permanent signing secrets are configured. A missing signing configuration fails publication. No Gemini provider secret is needed by the build. The normal app updater downloads artifacts/app-debug.apk; Beta downloads the complete APK from the beta-latest release, built from the same main branch. There is no separately maintained Beta source branch.

Enter your own Gemini key in AI and weather settings. It is encrypted with Android Keystore using the existing storage format; neither app embeds a provider credential or reads one from the build environment. Existing user-configured keys remain intact across updates. Users who relied on the former shared Beta credential must configure their own key after this update. Local commands, offline voice features and typed device tasks remain available without Gemini. Other integrations retain their normal settings.

After successful compilation, signature, package, version and launcher-label verification, the Beta download is:
https://github.com/muddigger782012/J.A.R.V.I.S.-/releases/download/beta-latest/Beta.apk

Pull-request checks compile debug plus optimized release and Beta APKs without signing secrets or provider credentials, verify package identities and bundled voices, and scan DEX/resources/text assets for known provider key patterns and the removed credential field. Those unsigned release/Beta outputs validate compilation; they are not installable signed updates. The publishing workflow verifies both apps against the existing signing certificate before upload. Source and APK checks do not establish actual device behavior. Test install, Gemini response, voice, accessibility, file sharing, shortcuts and updates on the tester device.

## First-launch introduction

Beta opens a five-step themed setup guide on first launch: introduction and user-key configuration status, microphone and notification permission, appearance and voice preferences, optional Android background/Accessibility/default-assistant settings, and a real Gemini connection test. Finish with typed chat or explicitly start background voice. Permission denial never prevents typed use. Finish later dismisses automatic prompts and keeps the current step for resumption. The menu's Introduction and setup entry reopens the guide. Setup completion is retained across updates, and the guide is not automatically shown in the original app.

Android controls its own permission/settings screens. The guide opens these screens but cannot approve permissions, remove battery restrictions or select a default assistant itself. Actual AI test responses are displayed rather than treating key presence as a successful network check.

## Bundled offline voices

Beta now embeds the exact verified Kokoro archive (including license and espeak data) in its signed APK. First launch automatically unpacks it in a worker thread, verifies archive SHA256 and file sizes, and publishes the installed directory only after validation. The existing process/file locks serialize voice installation. Setup and the voice picker show preparation status and offer a local retry after storage or preparation failure. The original app retains its existing opt-in download behavior.

The archive is reused across updates when its verified ready marker matches. Incomplete staging is retried and a replaced pack is recovered through an atomic backup swap. Beta never silently downloads the archive from the network when a bundled asset is expected.

The larger complete APK is published as the stable beta-latest/Beta.apk release asset rather than a Git source file. Existing installs from before this change should install the complete APK through the new direct link once to switch to the release update channel. The historical raw Beta APK is kept for compatibility but is no longer the current distribution link.

