"""Reject new theme bypasses while retaining an explicit legacy baseline."""
import collections
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = ROOT / "scripts/ui_theme_baseline.json"


def findings():
    result = []
    roots = ["app/src/main/java", "app/src/main/res/layout", "app/src/main/res/drawable"]
    for folder in roots:
        for path in sorted((ROOT / folder).rglob("*")):
            if path.suffix not in (".kt", ".xml"):
                continue
            source = path.read_text()
            relative = path.relative_to(ROOT).as_posix()
            for number, line in enumerate(source.splitlines(), 1):
                if re.search(r"(?:androidx\.appcompat\.app\.)?AlertDialog\.Builder\(", line) and path.name != "ThemedDialogBuilder.kt":
                    result.append((relative, number, line.strip(), "Use ThemedDialogBuilder"))
                colors = re.findall(r"#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?\b", line)
                # Neutral vector masks are tinted at their usage site.
                if path.suffix == ".xml" and "<vector" in source and all(c.upper() in ("#FFFFFF", "#FFFFFFFF", "#000000", "#FF000000") for c in colors):
                    colors = []
                if colors:
                    result.append((relative, number, line.strip(), "Use active theme color attributes"))
    return result


def main():
    allowed = collections.Counter(json.loads(BASELINE.read_text()))
    errors = []
    for path, number, line, reason in findings():
        key = json.dumps([path, line, reason], ensure_ascii=False)
        if allowed[key]:
            allowed[key] -= 1
        else:
            errors.append(f"{path}:{number}: {reason}: {line}")
    if errors:
        print("Theme check failed:\n" + "\n".join(errors))
        return 1
    print("Theme check passed: no new hardcoded UI colors or unthemed dialog builders.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
