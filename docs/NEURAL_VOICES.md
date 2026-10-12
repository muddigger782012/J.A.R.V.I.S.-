# Dedicated offline male voices

JARVIS Original uses Kokoro v0.19 presets George (British male, speaker 9), Lewis (British male, speaker 10) and Michael (American male, speaker 6). These IDs belong to the v0.19 pack; they differ from Kokoro v1.0 IDs. Old Android Original IDs migrate to George when the pack is installed. Default Female remains an Android TTS voice with its separately saved selection.

Settings → Assistant voice → Download male voices downloads the upstream full-precision ONNX package once. The release workflow verifies model contents, runs all three speakers and embeds the exact archive SHA256 and byte counts in the APK. The device verifies those bytes before extraction, rejects escaping paths and links, enforces size limits and atomically installs only a complete pack. No speech text leaves the device for these voices. Download cancellation and failed attempts remove staging files. Closing the screen cancels an active download; a retry starts from the beginning.

Preview and normal/background speech share the same catalog and native configuration. Saving Original records the exact preset ID; the Android voice object cannot overwrite it. Female preview and speech continue to use Android. Until a pack is installed, speech reports its temporary female fallback. A missing or failed native model is not presented as a working male voice.

Kokoro uses its own voice register at speed 1.0, with no pitch shifting. The full-precision pack avoids the Android INT8 artifact report in sherpa-onnx issue 3754. This is not actor cloning. Upstream Kokoro license files are retained in the downloaded package; model weights are Apache 2.0 and the runtime/data keep their own upstream licenses.

References:
- https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html
- https://github.com/k2-fsa/sherpa-onnx/issues/3754
- https://huggingface.co/hexgrad/Kokoro-82M

Validation: CI model synthesis smoke checks, Android compilation and unit tests, UI theme contract. Device download, AudioTrack playback, perceived quality, background replies, cancellation and wake/listening timing still require a phone audition. Linux synthesis does not establish Android audio behavior or gender perception on a particular device.

## Repeated phrases and voice diagnostics

Wake acknowledgments, introductions and the fixed settings audition may reuse a bounded private PCM cache. General conversational replies are not cached. Keys include the exact model archive fingerprint, preset ID, fixed speech speed and phrase, so editing a phrase or choosing another voice cannot replay the old variant. Cache hits skip model initialization and synthesis. Corrupt or truncated entries are rejected and regenerated; storage failure only disables caching, not speech. The cache is limited to 8 MiB and Android may evict it normally.

Settings → Assistant voice → Voice diagnostics shows the configured engine, saved preset, verified pack state, speech switches and the latest recorded native preview/assistant audio events. Copy for debug copies that report locally. It excludes phrase contents, conversation text and credentials. Timing is time from the request to the first successfully queued audio frames, not a measurement of sound reaching the speaker. Events are historical snapshots, not a current microphone/playback indicator. Clear phrase audio cache preserves the downloaded voice pack and settings.

Device check: audition the same preset twice, then inspect cache hits and first queued audio timing. Change the preset or wake phrase and verify a fresh variant. Test the background wake response, muting and cancellation. Automated cache tests cover exact-key isolation, corrupt/truncated data, finite samples, bounded eviction and clearing.

## Selection and playback repair

Voice picking stays open until Done, and Use this voice saves without leaving Assistant voice settings. Native speech uses PCM16 on the media route, with audio focus and a bounded playback completion wait. Media volume and mute state are included in Voice diagnostics. If the native engine fails, regular/background speech tries the saved female voice for that response, while retaining the selected male preset and honoring the response channel's mute switch and completion callback. Preview errors remain visible in settings.

Regression coverage includes an Android emulator test that chooses Lewis without closing settings and a focused PCM16 AudioTrack test using synthetic audio. These tests check Android frame queuing and completion, not sound at the user's speaker or the downloaded model's operation on Samsung hardware. The cause of the reported silence has not yet been confirmed by a phone diagnostic report.
