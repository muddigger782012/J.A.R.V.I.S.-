# Personal J.A.R.V.I.S. — Uncensored Liquid AI LFM3 (first integration milestone)

This is an **opt-in, local-only** conversational provider for Personal `com.assistant.core`. It does not change Beta and does not replace existing approved-action handling. It accepts **text** only; the LFM3 model never receives a shell, installer, accessibility, or device-control tool.

## What is actually implemented

- AI settings: select `Local LFM3`, choose AtomicChat or OpenIntelligenceNet uncensored 1.7B GGUF, enable local runtime, and set the loopback port (default 8080).
- Both ordinary chat and ambient companion text responses can use the selected local provider.
- Before requesting a reply, the app verifies that `GET http://127.0.0.1:PORT/v1/models` lists the **expected model alias**. It will not silently send a prompt to a different local model.
- Local calls use `POST /v1/chat/completions`; only `choices[0].message.content` is returned as text. No model-derived lessons or actions are accepted. Timeouts, response limits and cancellation checks are applied through existing response-turn routing.
- Gemini and an already configured gateway are optional fallback providers. If `Local LFM3` is not selected, cloud routing stays as before.
- AI diagnostics include a local model/probe check.
- The only allowed HTTP address is `127.0.0.1` on this Android phone. No local model prompt is sent to a remote HTTP host by this client.

## What is **not** implemented

The GGUF weights and native inference engine are **not bundled** inside the J.A.R.V.I.S. APK at this stage. Selecting a model in settings does not install or load it. The test below requires a separate Android-local `llama-server` process, for example one built with a recent LFM3-capable llama.cpp in Termux. This is a working Android-local inference bridge, **not yet fully embedded on-device inference in one APK**. Offline speech capture and speech synthesis remain separate systems.

The models are third-party community-derived checkpoints; verify their license and security before redistribution. They are not official Liquid AI uncensored releases.

## Run on Android using Termux (technical pilot)

A sufficiently recent build of llama.cpp is required for LFM3. On the phone, install a maintained Termux distribution and its native build toolchain. If `llama-server` is not available as a package, build the server from current `ggml-org/llama.cpp` sources following the upstream Android/Termux instructions:
https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md

Once `llama-server` is present on your phone, start **one** of the following commands in Termux.

AtomicChat (first choice, Q4):
```sh
llama-server -hf AtomicChat/LFM3-1.7B-Uncensored-GGUF:Q4_K_M \
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
4. Turn on **Enable on-phone LFM3 runtime**. Leave port at **8080**.
5. Save, then open **Intelligence → Test local LFM3 runtime**.
6. Open text chat, then try a normal technical question. After text chat works, test ambient companion separately.

If the local model is absent, the selected alias mismatches, or Android kills the Termux process, the app reports a local-provider failure and can try the cloud fallback if enabled. A connection test is evidence of a *past* successful request, not of indefinite server availability.

**Host policy is unchanged**: the conversational model may answer openly, but it cannot itself authorize shell, package install, permission, screen, or other privileged device operations.

## Next integration milestone

Bring llama.cpp into the Android build via native CMake/JNI or the upstream llama.android integration so the model can load directly in the J.A.R.V.I.S. process. Add managed GGUF download with checksum verification, load/unload controls, memory safeguards, and CPU/GPU benchmarking on a physical Samsung arm64 device. Then consider multimodal and audio GGUF separately. Do not ship the proof-of-concept loopback bridge as a fully self-contained local AI implementation.
