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
