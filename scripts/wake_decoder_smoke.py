"""Exercise packaged FP32 KWS models; this does not simulate Android's microphone."""
from pathlib import Path
import sys
import tempfile
import time
import wave

import numpy as np
import sentencepiece as spm
import sherpa_onnx


def run(model_dir: Path) -> None:
    sp = spm.SentencePieceProcessor(model_file=str(model_dir / "bpe.model"))
    pieces = sp.encode("HEY JARVIS", out_type=str)
    vocabulary = {line.rsplit(" ", 1)[0] for line in (model_dir / "tokens.txt").read_text().splitlines()}
    assert pieces and all(piece in vocabulary for piece in pieces), pieces
    wake_keywords = " ".join(pieces) + " @HEY_JARVIS\n"
    # Verify the APK's generated wake tokens when running in CI.
    packaged = model_dir / "jarvis-keywords.txt"
    if packaged.exists():
        assert packaged.read_text() == wake_keywords

    def create(keywords: str):
        def model(prefix: str):
            return str(next(p for p in sorted(model_dir.glob(prefix + "*.onnx")) if not p.name.endswith(".int8.onnx")))
        return sherpa_onnx.KeywordSpotter(
            tokens=str(model_dir / "tokens.txt"), encoder=model("encoder-"),
            decoder=model("decoder-"), joiner=model("joiner-"),
            keywords_file=keywords, num_threads=1, provider="cpu",
            keywords_score=3.0, keywords_threshold=0.037, num_trailing_blanks=1,
        )

    def feed(kws, stream, samples):
        detections = []
        for offset in range(0, len(samples), 1600):
            stream.accept_waveform(16000, samples[offset:offset + 1600])
            while kws.is_ready(stream):
                kws.decode_stream(stream)
                result = kws.get_result(stream)
                if result:
                    detections.append(result)
                    kws.reset_stream(stream)
        return detections

    started = time.monotonic()
    with tempfile.TemporaryDirectory() as tmp:
        keyword_file = Path(tmp) / "wake.txt"
        keyword_file.write_text(wake_keywords)
        kws = create(str(keyword_file))
        assert not feed(kws, kws.create_stream(), np.zeros(32000, dtype=np.float32)), "Silence triggered wake word"
    # Known speech checks the decoder/result path, independently of the wake phrase.
    kws = create(str(model_dir / "test_wavs/test_keywords.txt"))
    for name, expected in [("0.wav", "LIGHT UP"), ("1.wav", "LOVELY CHILD")]:
        with wave.open(str(model_dir / "test_wavs" / name)) as wav:
            assert wav.getframerate() == 16000 and wav.getsampwidth() == 2 and wav.getnchannels() == 1
            samples = np.frombuffer(wav.readframes(wav.getnframes()), dtype="<i2").astype(np.float32) / 32768
        samples = np.concatenate([samples, np.zeros(16000, dtype=np.float32)])
        found = feed(kws, kws.create_stream(), samples)
        assert any(expected in result.upper() for result in found), (name, expected, found)
        print(name, found)
    print(f"FP32 native decoder, silence rejection, wake tokens, and known speech passed in {time.monotonic() - started:.2f}s")


if __name__ == "__main__":
    run(Path(sys.argv[1]))
