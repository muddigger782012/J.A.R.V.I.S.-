"""Check existing Beta credential injection without printing credential values."""
import argparse
import json
import os
from pathlib import Path
import re
import zipfile


def verify(build_config, apks, expected, beta):
    text = Path(build_config).read_text()
    match = re.search(r'BETA_GEMINI_API_KEY\s*=\s*("(?:[^"\\]|\\.)*")\s*;', text)
    if match is None:
        raise ValueError("Generated Beta configuration field is missing")
    configured = json.loads(match.group(1))
    if beta and not expected:
        raise ValueError("Beta publishing requires its existing configured credential")
    if configured != (expected if beta else ""):
        raise ValueError("Generated variant credential configuration does not match its input")
    for apk in apks:
        with zipfile.ZipFile(apk) as package:
            present = bool(expected) and any(
                expected.encode("utf-8") in package.read(name)
                for name in package.namelist() if re.fullmatch(r"classes\d*\.dex", name)
            )
        if beta != present:
            raise ValueError("Compiled variant credential isolation/presence check failed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-config", required=True)
    parser.add_argument("--beta", action="store_true")
    parser.add_argument("apks", nargs="+")
    args = parser.parse_args()
    try:
        verify(args.build_config, args.apks, os.getenv("JARVIS_BETA_GEMINI_API_KEY", ""), args.beta)
    except (ValueError, OSError, zipfile.BadZipFile, json.JSONDecodeError):
        # Parse errors can include input fragments. Never emit their exception text.
        raise SystemExit("Variant API configuration verification failed; no credential values logged")
    print("Verified variant API configuration presence/isolation; no credential values logged")
