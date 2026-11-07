#!/usr/bin/env python3
"""Fail closed on any Personal signed APK before an in-app LFM3 update is offered."""
import argparse
import sys
import zipfile
from pathlib import Path

from personal_release import ABIS, identity, signers, verify_archive


def verify(directory: Path, baseline: Path, code: int, version: str) -> None:
    assert code > 0 and version == f"3.6.{code}", "Unexpected Personal release version"
    assert baseline.is_file(), "The installed-update signing baseline is missing"
    trusted = signers(baseline)
    expected = {f"app-{abi}-release.apk" for abi in ABIS}
    observed = {p.name for p in directory.glob("*.apk")}
    assert observed == expected, f"Unexpected ABI APKs: {observed ^ expected}"
    for abi in ABIS:
        path = directory / f"app-{abi}-release.apk"
        assert path.is_file() and path.stat().st_size > 0, f"Missing APK: {abi}"
        package, badging = identity(path)
        assert int(package["versionCode"]) == code, "Incorrect version code"
        assert package["versionName"] == version, "Incorrect version name"
        assert "native-code: '" + abi + "'" in badging, f"Wrong ABI: {abi}"
        assert signers(path) == trusted, f"Wrong Personal signing certificate: {abi}"
        verify_archive(path, abi)
        with zipfile.ZipFile(path) as apk:
            names = set(apk.namelist())
            lfm3 = f"lib/{abi}/libjarvis_lfm3.so"
            if abi == "arm64-v8a":
                assert lfm3 in names, "ARM64 release omitted the embedded LFM3 native engine"
                assert apk.getinfo(lfm3).file_size > 1000, "Embedded LFM3 engine is empty"
                assert "lib/arm64-v8a/libllama.so" in names, "The GGUF inference dependency is missing"
            else:
                assert not any(n.endswith("/libjarvis_lfm3.so") for n in names), (
                    f"Embedded LFM3 leaked into unsupported ABI: {abi}"
                )
        print(f"Verified Personal signed {abi}: version={version}, bytes={path.stat().st_size}, "
              f"signer matches baseline, native_LFM3={abi == 'arm64-v8a'}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--version-name", required=True)
    args = parser.parse_args()
    verify(args.directory, args.baseline, args.version_code, args.version_name)


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, ValueError, OSError, zipfile.BadZipFile) as exc:
        print(f"REFUSING PERSONAL IN-APP UPDATE: {exc}", file=sys.stderr)
        sys.exit(1)
