"""Verify the upstream FP32 Kokoro pack and bind release download metadata to its bytes."""
import argparse
import hashlib
import json
from pathlib import Path
import tarfile
import urllib.request

SOURCE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2"


def prepare(directory, metadata):
    directory.mkdir(parents=True, exist_ok=True)
    archive = directory / "kokoro.tar.bz2"
    if not archive.exists():
        with urllib.request.urlopen(SOURCE, timeout=60) as source, archive.open("wb") as target:
            while chunk := source.read(1024 * 1024):
                target.write(chunk)
    with archive.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    unpacked = 0
    with tarfile.open(archive) as tar:
        for member in tar.getmembers():
            parts = Path(member.name).parts
            if not parts or parts[0] != "kokoro-en-v0_19" or ".." in parts or not (member.isfile() or member.isdir()):
                raise ValueError(f"Unexpected model archive entry: {member.name}")
            if member.isfile():
                unpacked += member.size
        tar.extractall(directory, filter="data")
    root = directory / "kokoro-en-v0_19"
    for name in ("model.onnx", "voices.bin", "tokens.txt", "LICENSE"):
        assert (root / name).stat().st_size > 0
    assert (root / "espeak-ng-data").is_dir()
    assert "Apache" in (root / "LICENSE").read_text()
    metadata.parent.mkdir(parents=True, exist_ok=True)
    metadata.write_text(json.dumps({"url": SOURCE, "sha256": digest, "bytes": archive.stat().st_size,
                                    "unpacked_bytes": unpacked}, indent=2) + "\n")
    print(f"Verified model pack: {archive.stat().st_size / 1048576:.1f} MiB download; SHA256 {digest}")
    return root


def smoke(root):
    import numpy as np
    import sherpa_onnx
    config = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(model=str(root / "model.onnx"),
            voices=str(root / "voices.bin"), tokens=str(root / "tokens.txt"), data_dir=str(root / "espeak-ng-data")),
        num_threads=2), max_num_sentences=1)
    assert config.validate()
    tts = sherpa_onnx.OfflineTts(config)
    assert tts.num_speakers == 11
    fingerprints = []
    for name, speaker in (("George", 9), ("Lewis", 10), ("Michael", 6)):
        audio = tts.generate("Systems are ready. Shall we get to work?", sid=speaker, speed=1.0)
        samples = np.asarray(audio.samples)
        assert audio.sample_rate > 0 and len(samples) > audio.sample_rate
        assert np.isfinite(samples).all() and float(np.mean(samples * samples)) > 1e-7
        fingerprints.append(hashlib.sha256(samples.tobytes()).hexdigest())
        print(f"{name}: speech generated, {len(samples) / audio.sample_rate:.1f}s")
    assert len(set(fingerprints)) == 3, "Male presets produced identical speech"


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--smoke", action="store_true")
    args = parser.parse_args()
    root = prepare(args.directory, args.metadata)
    if args.smoke:
        smoke(root)
