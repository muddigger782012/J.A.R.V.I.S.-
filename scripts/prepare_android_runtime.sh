#!/usr/bin/env bash
set -euo pipefail

# Shared release/PR preparation. Keep the existing runtime and complete KWS
# archive identical so PR universal-size measurements include production assets.
runtime_temp="$(mktemp -d "${RUNNER_TEMP:-/tmp}/jarvis-runtime.XXXXXX")"
trap 'rm -rf "$runtime_temp"' EXIT
mkdir -p app/libs app/src/main/assets
curl --fail --location --retry 3 --retry-all-errors \
    --output app/libs/sherpa-onnx-1.13.8.aar \
    https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar
test -s app/libs/sherpa-onnx-1.13.8.aar
unzip -tq app/libs/sherpa-onnx-1.13.8.aar
curl --fail --location --retry 3 --retry-all-errors \
    --output "$runtime_temp/sherpa-kws.tar.bz2" \
    https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2
tar -xjf "$runtime_temp/sherpa-kws.tar.bz2" -C app/src/main/assets
model_dir='app/src/main/assets/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01'
if [ ! -d "$model_dir" ]; then
    candidates=(app/src/main/assets/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01*)
    if [ "${#candidates[@]}" -ne 1 ] || [ ! -d "${candidates[0]}" ]; then
        echo 'Expected exactly one existing Sherpa KWS model directory' >&2
        exit 1
    fi
    mv "${candidates[0]}" "$model_dir"
fi
python3 -m pip install --quiet sentencepiece sherpa-onnx==1.13.8 numpy
python3 - <<'PY'
from pathlib import Path
import sentencepiece as spm

d = Path('app/src/main/assets/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01')
model = d / 'bpe.model'
if not model.is_file():
    raise SystemExit('English KWS BPE tokenizer is missing')
sp = spm.SentencePieceProcessor(model_file=str(model))
pieces = sp.encode('HEY JARVIS', out_type=str)
valid = {line.rsplit(' ', 1)[0] for line in (d / 'tokens.txt').read_text().splitlines() if line.strip()}
if not pieces or any(piece not in valid for piece in pieces):
    raise SystemExit('HEY JARVIS cannot be represented by the existing model vocabulary')
keyword = ' '.join(pieces) + ' @HEY_JARVIS\n'
(d / 'jarvis-keywords.txt').write_text(keyword)
for prefix in ('encoder-', 'decoder-', 'joiner-'):
    if not any(p.name.startswith(prefix) and p.name.endswith('.onnx')
               and not p.name.endswith('.int8.onnx') for p in d.iterdir()):
        raise SystemExit('Missing packaged FP32 ' + prefix + 'ONNX model')
print('Prepared existing Sherpa Android runtime and complete production KWS assets')
PY
