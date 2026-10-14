"""Measure universal legacy APKs with the exact prepared production KWS assets.

This accepts unsigned PR APKs for packaging/size evidence only. It does not assert
signature compatibility or replace the signed production staging checks.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

from stage_update_channels import ABIS, MAX_LEGACY_BYTES, file_digest

UNSIGNED_HEADROOM_BYTES = 64_000


def native_abis(package):
    return {name.split('/')[1] for name in package.namelist()
            if re.fullmatch(r'lib/[^/]+/[^/]+\.so', name)}


def verify(apk, model_dir, runtime_aar, unsigned=True):
    apk, model_dir, runtime_aar = Path(apk), Path(model_dir), Path(runtime_aar)
    source_files = [path for path in model_dir.rglob('*') if path.is_file()]
    required = ['tokens.txt', 'bpe.model', 'jarvis-keywords.txt']
    if any(not (model_dir / name).is_file() for name in required):
        raise ValueError('Prepared production KWS tokenizer/wake assets are incomplete')
    for prefix in ('encoder-', 'decoder-', 'joiner-'):
        if not any(path.name.startswith(prefix) and path.name.endswith('.onnx')
                   and not path.name.endswith('.int8.onnx') for path in source_files):
            raise ValueError('Prepared production FP32 KWS models are incomplete')
    size = apk.stat().st_size
    headroom = UNSIGNED_HEADROOM_BYTES if unsigned else 0
    if size < 100_000 or size + headroom >= MAX_LEGACY_BYTES:
        raise ValueError(f'Legacy APK size {size} plus signing headroom {headroom} must be below {MAX_LEGACY_BYTES} bytes')
    with zipfile.ZipFile(apk) as package, zipfile.ZipFile(runtime_aar) as runtime:
        names = package.namelist()
        if len(names) != len(set(names)):
            raise ValueError('APK contains ambiguous duplicate ZIP entries')
        if native_abis(package) != set(ABIS):
            raise ValueError('Legacy alias does not contain all three supported native ABIs')
        runtime_libraries = [name for name in runtime.namelist()
                             if re.fullmatch(r'jni/[^/]+/[^/]+\.so', name)
                             and name.split('/')[1] in ABIS]
        if {name.split('/')[1] for name in runtime_libraries} != set(ABIS):
            raise ValueError('Prepared runtime lacks a supported native ABI')
        if any('lib/' + name.removeprefix('jni/') not in names for name in runtime_libraries):
            raise ValueError('Legacy alias is missing an existing Sherpa native library')
        for path in source_files:
            name = 'assets/' + model_dir.name + '/' + path.relative_to(model_dir).as_posix()
            if path.stat().st_size <= 0 or package.getinfo(name).file_size != path.stat().st_size:
                raise ValueError('Legacy APK KWS asset size does not match production preparation')
            with package.open(name) as packaged:
                if hashlib.file_digest(packaged, 'sha256').hexdigest() != file_digest(path):
                    raise ValueError('Legacy APK KWS bytes differ from production preparation')
    return {'apk': str(apk), 'sizeBytes': size, 'limitBytes': MAX_LEGACY_BYTES,
            'signingHeadroomBytes': headroom, 'remainingBytes': MAX_LEGACY_BYTES - size - headroom,
            'nativeAbis': list(ABIS), 'verifiedKwsAssets': len(source_files),
            'sha256': file_digest(apk), 'signedCompatibilityVerified': False}


def find_universal(directory):
    universal = []
    for apk in Path(directory).glob('*.apk'):
        with zipfile.ZipFile(apk) as package:
            if native_abis(package) == set(ABIS):
                universal.append(apk)
    if len(universal) != 1:
        raise ValueError('Expected exactly one universal APK for each legacy variant')
    return universal[0]


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--original-dir', required=True)
    parser.add_argument('--bridge-dir', required=True)
    parser.add_argument('--model-dir', required=True)
    parser.add_argument('--runtime-aar', required=True)
    parser.add_argument('--report', required=True)
    args = parser.parse_args()
    try:
        measurements = {channel: verify(find_universal(directory), args.model_dir, args.runtime_aar)
                        for channel, directory in [('jarvis', args.original_dir), ('betaBridge', args.bridge_dir)]}
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(json.dumps(measurements, indent=2) + '\n')
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        raise SystemExit('Production-sized legacy APK check failed: ' + str(error))
    for channel, measurement in measurements.items():
        print(f"{channel}: {measurement['sizeBytes']} bytes; remaining signed-update budget {measurement['remainingBytes']} bytes; production KWS/native packaging verified")
