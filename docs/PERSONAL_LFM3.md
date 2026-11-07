# Personal J.A.R.V.I.S. — Uncensored Liquid AI LFM3 (experimental embedded runtime)

This is an **opt-in, local-only** conversational provider for Personal `com.assistant.core`. It does not change Beta and does not replace existing approved-action handling. It accepts **text** only; the LFM3 model never receives a shell, installer, accessibility, or device-control tool.

## What is actually implemented

- AI settings: select `Local LFM3`, choose AtomicChat or OpenIntelligenceNet uncensored 1.7B GGUF, enable local runtime, and set the loopback port (default 8080).
- In-app management for both pinned Q4 files: explicit download, pause/resume, SHA-256 verification, and delete. The weights live in the **Personal application's private no-backup storage** and are not bundled in the APK.
- Both ordinary chat and ambient companion text responses can use the selected local provider.
- In ordinary Android builds, the loopback server is still required: before requesting a reply, the app checks `GET http://127.0.0.1:PORT/v1/models` for the expected model alias. In explicitly native-enabled ARM64 builds, it instead first loads the verified GGUF directly and generates text through JNI, without any HTTP calls.
- Loopback calls use `POST /v1/chat/completions`; only `choices[0].message.content` is returned as text. Embedded calls use a separate JNI interface without a server. No model-derived lessons or actions are accepted. Timeouts, response limits and cancellation checks are applied through existing response-turn routing.
- Gemini and an already configured gateway are optional fallback providers. If `Local LFM3` is not selected, cloud routing stays as before.
- AI diagnostics include a local model/probe check.
- The only allowed HTTP address is `127.0.0.1` on this Android phone. No local model prompt is sent to a remote HTTP host by this client.
- The weight downloader uses preapproved public Hugging Face HTTPS URLs, requires SHA-256 plus expected size metadata, verifies every byte before marking a model ready, and rejects unsafe resumption ranges. Downloads require Internet and approximately 1.1 GB per variant. Close the settings page to pause and resume later.
- **Privacy:** if provider fallback is enabled, a failed local request may cause the same question to be sent to configured Gemini/gateway. Disable fallback to guarantee conversation stays on-phone even when the local server is down.

## What is **not** implemented

By default, the APK **does not** contain the llama.cpp native inference runtime or bundled GGUF weights. A separate, **experimental ARM64 native build** can be produced with `-PjarvisEmbeddedLfm3=true`; this adds an in-process JNI llama.cpp runtime compiled from an exact upstream source revision. When those native libraries are present, selecting Local LFM3 and downloading a verified GGUF permits automatic in-app model loading and text generation with no Termux dependency. This path is under CI and device validation, **not yet released as a signed Personal update**. Offline speech capture and speech synthesis remain separate systems. Termux compatibility mode still needs its own copy of the GGUF because it cannot read the app-private file.

The models are third-party community-derived checkpoints; verify their license and security before redistribution. They are not official Liquid AI uncensored releases.

## Run on Android using Termux (technical pilot)

A sufficiently recent build of llama.cpp is required for LFM3. On the phone, install a maintained Termux distribution and its native build toolchain. If `llama-server` is not available as a package, build the server from current `ggml-org/llama.cpp` sources following the upstream Android/Termux instructions:
https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md

Once `llama-server` is present on your phone, start **one** of the following commands in Termux.

AtomicChat (first choice, Q4):
```sh
llama-server -hf AtomicChat/LFM3-1.7B-Uncensored-GGUF:AD-Q4_K_M \
  --host 127.0.0.1 --port 8080 --alias jarvis-lfm3-atomic \
  --ctx-size 4096 --threads 4
```

OpenIntelligenceNet Extreme (alternate, Q4):
```sh
llama-server -hf OpenIntelligenceNet/LFM3-1.7B-Uncensored-Extreme-GGUF:Q4_K_M \
  --host 127.0.0.1 --port 8080 --alias jarvis-lfm3-extreme \
  --ctx-size 4096 --threads 4
```

If the installed server cannot use `-hf`, download the appropriate Q4 GGUF from its Hugging Face model page, then use `-m /absolute/path/model.gguf` instead. Check the runtime's `--help` for supported flags. The initial model download needs internet; subsequent inference is local. This is intentionally separate from the signed app update and does not require a Gemini key.

Model sources:
- https://huggingface.co/AtomicChat/LFM3-1.7B-Uncensored-GGUF
- https://huggingface.co/OpenIntelligenceNet/LFM3-1.7B-Uncensored-Extreme-GGUF

To check the loaded alias from Termux:
```sh
curl -fsS http://127.0.0.1:8080/v1/models
```
The response must include the exact matching alias `jarvis-lfm3-atomic` or `jarvis-lfm3-extreme`. Only one chosen model should be running on port 8080 at a time.

## Configure Personal J.A.R.V.I.S.

1. Open **AI settings**.
2. Choose **Conversation provider: Local LFM3**.
3. Choose the **same uncensored variant** that Termux has loaded.
4. Turn on **Enable local LFM3 conversation**. Leave port at **8080**.
5. Optionally tap **Download selected uncensored GGUF** for the embedded-native milestone. The app verifies the SHA-256 on completion. Use **Pause** to stop gracefully; re-open and download again to resume. **Verify** rehashes the file and **Delete** removes it. These files remain app-private; downloading does not start a model.
6. Save, then open **Intelligence → Test local LFM3 runtime**.
7. Open text chat, then try a normal technical question. After text chat works, test ambient companion separately.

If the local model is absent, the selected alias mismatches, or Android kills the Termux process, the app reports a local-provider failure and can try the cloud fallback if enabled. A connection test is evidence of a *past* successful request, not of indefinite server availability.

**Host policy is unchanged**: the conversational model may answer openly, but it cannot itself authorize shell, package install, permission, screen, or other privileged device operations.

## Verified model filenames

- AtomicChat: `LFM3-1.7B-Uncensored-AD-Q4_K_M.gguf`
- OpenIntelligenceNet Extreme: `LFM3-1.7B-Uncensored-Q4_K_M-imatrix.gguf`

These are fixed, allowlisted paths and not arbitrary URLs. Download stores `atomic.gguf` or `extreme.gguf` in Android's `noBackupFilesDir/lfm3-models`, with a SHA-256 receipt. The file is not exposed as a public network endpoint or automatically sent to the model provider.

## Experimental in-app ARM64 native runtime

The native runner is isolated behind the Gradle property `-PjarvisEmbeddedLfm3=true`, and the matching source and validation workflow live at:

- `app/src/main/cpp/jarvis_lfm3.cpp`: JNI model initialization, CPU-only GGUF load, 3072-token context, 256-token response limit, cancellation and unload.
- `app/src/main/java/com/assistant/core/services/EmbeddedLfm3Client.kt`: verified model path selection, preflight available-memory guard, local conversation and lifecycle handling.
- `.github/workflows/native-lfm3-android.yml`: checks out pinned upstream `ggml-org/llama.cpp` commit `e95426f16982054c379ca7bdc8186996dd22f0fe`, compiles using Android NDK and CMake, runs unit tests, and uploads a separate ARM64 **debug** APK artifact on success.

Normal Personal/Beta builds do not fetch llama.cpp and retain their existing download sizes. Beta's `EMBEDDED_LFM3` flag is always false. No native model may directly call privileged device APIs. The native build does **not** embed a 1.1GB model into the APK: weights are downloaded explicitly and verified on-device.

The native implementation is still experimental. Successful compilation alone does not establish text quality, battery use, safe process memory, microphone stability or suitability for the production signing key.

## Release and hardware acceptance

Before merging or publishing this as an in-app update:

1. Native-enabled ARM64 CMake/NDK build, JVM tests, Android lint and theme checks all pass on the same commit.
2. Signed-app-compatible test on the Samsung phone: download GGUF, verify it, start conversation with Wi-Fi and mobile data OFF, confirm a real generated reply without Termux.
3. Measure cold load time, warm token latency, RAM pressure and long-session reliability; repeat after model switch, deletion, app restart and cancellation.
4. Regression-test push-to-talk, wake word, Ambient Companion Mode and audio playback independently. The voice recognizer remains unchanged by this feature.
5. Validate that provider fallback OFF never sends conversation to a cloud model, and that privileged actions still require existing host approval.
6. Only after successful acceptance, prepare a signed Personal update using the existing signing key. Do not merge an experimental debug build into the production update channel.

Current scope: Personal Android first, with a provider boundary designed for eventual Windows support. Multimodal models and offline speech generation are separate follow-up integrations.
