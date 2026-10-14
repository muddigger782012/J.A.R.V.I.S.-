# Beta: same code, preconfigured Gemini

The release and Beta APKs are compiled from the same source revision in the existing main-branch workflow. Beta retains all J.A.R.V.I.S. features, themes, voices, personality, and the Hey Jarvis wake word. Only Beta embeds the Gemini credential. Android requires a separate application identity (com.assistant.core.beta) so the app named Beta can install alongside the original.

Add the Gemini credential as repository Actions secret `JARVIS_BETA_GEMINI_API_KEY`. Run **Build Jarvis APK** on main once; every subsequent main update builds both apps automatically with the existing permanent signing key. The normal app updater downloads artifacts/app-debug.apk; Beta downloads artifacts/beta.apk from the same main branch. There is no separately maintained Beta source branch.

The key is injected during compilation, never committed in plaintext to this public repository. Embedded credentials are extractable from APKs. Use a dedicated tester credential with provider quota restrictions. A tester can override it with their own Gemini key; other integrations retain their normal settings.

After successful compilation, signature, package, version and launcher-label verification, the Beta download is:
https://raw.githubusercontent.com/muddigger782012/J.A.R.V.I.S.-/main/artifacts/beta.apk

Until the secret is configured, the workflow explicitly skips Beta and continues building the original app. No Beta APK or valid Beta download is promised before a successful build. Source checks do not establish actual device behavior. Test install, Gemini response, voice, accessibility, file sharing, shortcuts and updates on the tester device.

## First-launch introduction

Beta opens a five-step themed setup guide on first launch: introduction and configured-key status, microphone and notification permission, appearance and voice preferences, optional Android background/Accessibility/default-assistant settings, and a real Gemini connection test. Finish with typed chat or explicitly start background voice. Permission denial never prevents typed use. Finish later dismisses automatic prompts and keeps the current step for resumption. The menu's Introduction and setup entry reopens the guide. Setup completion is retained across updates, and the guide is not automatically shown in the original app.

Android controls its own permission/settings screens. The guide opens these screens but cannot approve permissions, remove battery restrictions or select a default assistant itself. Actual AI test responses are displayed rather than treating key presence as a successful network check.
