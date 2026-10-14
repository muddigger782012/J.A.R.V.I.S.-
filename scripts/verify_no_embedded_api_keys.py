"""Reject known provider credentials in APK code and text without logging values."""

import argparse
from pathlib import Path
import re
import zipfile


RETIRED_FIELD = b"BETA_GEMINI_API_KEY"
PROVIDER_CREDENTIALS = (
    re.compile(rb"AIza[0-9A-Za-z_-]{35}(?![0-9A-Za-z_-])"),
    re.compile(rb"sk-(?:proj-|svcacct-|ant-)?[0-9A-Za-z_-]{32,}"),
)
TEXT_EXTENSIONS = {".json", ".xml", ".txt", ".properties", ".html", ".js", ".css"}
BUILD_SOURCES = (
    "app/build.gradle",
    "app/src/main/java/com/assistant/core/services/ApiKeyStore.kt",
    ".github/workflows/build-jarvis.yml",
)


def verify_sources(root: Path) -> None:
    """Prevent the removed build-time Gemini secret from being reintroduced."""
    for filename in BUILD_SOURCES:
        if RETIRED_FIELD in (root / filename).read_bytes():
            raise ValueError(f"Build-time provider credential configuration in {filename}")


def verify(apk: Path) -> None:
    with zipfile.ZipFile(apk) as package:
        for entry in package.infolist():
            filename = entry.filename
            # DEX and Android resource tables contain encoded string literals.
            # Exclude native libraries and compressed model archives, whose random
            # bytes are unrelated to APK provider credential configuration.
            if not (filename.endswith(".dex") or filename == "resources.arsc"
                    or Path(filename).suffix.lower() in TEXT_EXTENSIONS):
                continue
            data = package.read(entry)
            if RETIRED_FIELD in data:
                raise ValueError(f"Retired embedded provider credential field in {filename}")
            if any(pattern.search(data) for pattern in PROVIDER_CREDENTIALS):
                raise ValueError(f"Provider credential pattern in {filename}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", nargs="?", type=Path)
    parser.add_argument("--source", type=Path, help="Check build configuration instead of an APK")
    args = parser.parse_args()
    if (args.apk is None) == (args.source is None):
        parser.error("provide exactly one APK path or --source directory")
    if args.source is not None:
        verify_sources(args.source)
        print("Verified build configuration excludes the retired provider credential")
    else:
        verify(args.apk)
        print(f"Verified known provider credentials are absent from {args.apk.name}")


if __name__ == "__main__":
    main()
