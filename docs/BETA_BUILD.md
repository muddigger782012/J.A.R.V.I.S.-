# Beta: same code, preconfigured Gemini

The release and Beta APKs are compiled from the same source revision in the existing main-branch workflow. Beta retains all J.A.R.V.I.S. features, themes, voices, personality, and the Hey Jarvis wake word. Only Beta embeds the Gemini credential. Android requires a separate application identity (com.assistant.core.beta) so the app named Beta can install alongside the original.

Add the Gemini credential as repository Actions secret `JARVIS_BETA_GEMINI_API_KEY`. Run **Build Jarvis APK** on main once; every subsequent main update builds both apps automatically with the existing permanent signing key. The normal app updater downloads artifacts/app-debug.apk; Beta downloads artifacts/beta.apk from the same main branch. There is no separately maintained Beta source branch.

The key is injected during compilation, never committed in plaintext to this public repository. Embedded credentials are extractable from APKs. Use a dedicated tester credential with provider quota restrictions. A tester can override it with their own Gemini key; other integrations retain their normal settings.

After successful compilation, signature, package, version and launcher-label verification, the Beta download is:
https://raw.githubusercontent.com/muddigger782012/J.A.R.V.I.S.-/main/artifacts/beta.apk

Until the secret is configured, the workflow explicitly skips Beta and continues building the original app. No Beta APK or valid Beta download is promised before a successful build. Source checks do not establish actual device behavior. Test install, Gemini response, voice, accessibility, file sharing, shortcuts and updates on the tester device.
