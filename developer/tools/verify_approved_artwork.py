"""Read-only verification of the owner's unchanged approved Developer artwork."""

import hashlib
import io
import json
import os
from pathlib import Path
import re
from zipfile import ZipFile


ARCHIVE = "developer/artwork/1-jarvis_approved_artwork.zip"
ARCHIVE_SHA256 = "92b34c80a031f82f0c4123e0e798313a2e6a271f1da107fcc8b4253178bf37f9"
ENTRIES = {
    "README.txt": (753, "5ab9702b527dca76380bfd5da3cc73b026a4807030b1f3ebff5e0a3ec4548c35"),
    "developer_header_reference.png": (102425, "5eda3e2efed91ee83761b989c06d4f926a5071ed790e0dc79acbec40b68c40d5"),
    "developer_reactor_launcher.png": (230066, "bcd0fe7499b57c43907e9f1b6cf82ca4dad84741403a0dacf666871ce7a5ff06"),
}
RESOURCES = {
    "developer/src/main/res/drawable-nodpi/developer_approved_header.jpg": "developer_header_reference.png",
    "developer/src/main/res/drawable-nodpi/developer_arc_reactor_icon.png": "developer_reactor_launcher.png",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_exact(root, relative, expected_size):
    path = root / relative
    require(not path.is_symlink() and path.is_file(), "Expected a regular approved artwork file")
    require(not any(parent.is_symlink() for parent in path.parents if parent != root),
            "Approved artwork paths must not contain symlinks")
    require(path.stat().st_size == expected_size, "Approved artwork byte count differs")
    return path.read_bytes()


def verify(root):
    root = Path(root).resolve()
    archive = read_exact(root, ARCHIVE, 332051)
    require(hashlib.sha256(archive).hexdigest() == ARCHIVE_SHA256, "Approved ZIP SHA-256 differs")
    approved = {}
    with ZipFile(io.BytesIO(archive)) as zipped:
        entries = zipped.infolist()
        require(len(entries) == 3 and sorted(entry.filename for entry in entries) == sorted(ENTRIES),
                "Approved ZIP must contain exactly the three original entry names")
        for entry in entries:
            size, expected_sha = ENTRIES[entry.filename]
            require(not entry.is_dir() and entry.file_size == size, "Approved ZIP entry byte count differs")
            data = zipped.read(entry)
            require(hashlib.sha256(data).hexdigest() == expected_sha, "Approved ZIP entry SHA-256 differs")
            approved[entry.filename] = data

    resources = root / "developer/src/main/res"
    for path in resources.rglob("*"):
        require(not path.is_symlink(), "Android resources must not contain symlinked artwork")
        require(path.suffix.lower() != ".zip", "Move artwork ZIPs outside Android resources")
        require(path.name.lower() != "developer_approved_header.png",
                "Remove the old duplicate approved-header PNG resource")

    records = []
    for relative, entry_name in RESOURCES.items():
        data = read_exact(root, relative, ENTRIES[entry_name][0])
        require(data == approved[entry_name], "Android artwork bytes differ from the approved ZIP entry")
        records.append({"path": relative, "approvedZipEntry": entry_name,
                        "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
    require(approved["developer_header_reference.png"].startswith(b"\xff\xd8\xff"),
            "Approved header must retain its original JPEG bytes")
    require(approved["developer_reactor_launcher.png"].startswith(b"\x89PNG\r\n\x1a\n"),
            "Approved launcher must retain its original PNG bytes")
    result = {"schemaVersion": 1, "archivePath": ARCHIVE, "archiveBytes": len(archive),
              "archiveSha256": ARCHIVE_SHA256, "archiveEntries": sorted(ENTRIES),
              "resources": records, "byteExact": True}
    source = os.environ.get("GITHUB_SHA")
    if source is not None:
        require(re.fullmatch(r"[0-9a-f]{40}", source) is not None, "Expected an immutable CI source SHA")
        result["sourceCommitSha"] = source
    return result


def main():
    root = Path(__file__).resolve().parents[2]
    result = verify(root)
    print("JARVIS_APPROVED_ARTWORK=" + json.dumps(result, sort_keys=True, separators=(",", ":")))


if __name__ == "__main__":
    main()
