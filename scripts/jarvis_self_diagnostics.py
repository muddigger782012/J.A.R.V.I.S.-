#!/usr/bin/env python3
"""Read-only, bounded source hygiene diagnostics for Jarvis (Phase 5)."""
import argparse
import json
from pathlib import Path

SUFFIXES = {".kt", ".java", ".py", ".gradle", ".xml"}
SKIP = {".git", ".gradle", "build", ".idea", "node_modules", "dist"}
MAX_BYTES = 1024 * 1024
MAX_FILES = 5000


def scan(root):
    findings = []
    scanned = 0
    for path in sorted(root.rglob("*")):
        if scanned >= MAX_FILES:
            findings.append({"severity": "info", "rule": "scan-limit", "path": ".", "detail": "File limit reached"})
            break
        if not path.is_file() or path.suffix not in SUFFIXES:
            continue
        if any(part in SKIP for part in path.relative_to(root).parts):
            continue
        if path.is_symlink():
            continue
        relative = path.relative_to(root).as_posix()
        size = path.stat().st_size
        if size > MAX_BYTES:
            findings.append({"severity": "info", "rule": "large-file", "path": relative, "detail": "Source exceeds 1 MiB review limit"})
            continue
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except (UnicodeError, OSError):
            findings.append({"severity": "info", "rule": "unreadable", "path": relative, "detail": "Unable to inspect UTF-8 text"})
            continue
        scanned += 1
        if len(lines) > 1200:
            findings.append({"severity": "medium", "rule": "large-source", "path": relative, "detail": f"{len(lines)} lines; consider splitting responsibilities"})
        for number, line in enumerate(lines, 1):
            if "TODO" in line or "FIXME" in line:
                findings.append({"severity": "low", "rule": "todo-marker", "path": relative, "line": number, "detail": "Review outstanding TODO/FIXME"})
                if len(findings) >= 300:
                    break
        if len(findings) >= 300:
            findings.append({"severity": "info", "rule": "finding-limit", "path": ".", "detail": "Finding limit reached"})
            break
    return {"schema_version": 1, "mode": "read-only", "scanned_files": scanned, "findings": findings}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    root = args.root.resolve()
    if not root.is_dir():
        parser.error("root must be an existing directory")
    report = scan(root)
    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print(f"Read-only Jarvis scan: {report['scanned_files']} source files, {len(report['findings'])} findings")
        for item in report["findings"][:25]:
            print(f"{item['severity']}: {item['path']} {item['rule']} — {item['detail']}")


if __name__ == "__main__":
    main()
