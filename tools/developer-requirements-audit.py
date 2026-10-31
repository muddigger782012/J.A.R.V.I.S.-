#!/usr/bin/env python3
"""Developer requirement evidence audit. Static presence is never called a completed feature."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHECKS = [
  [
    "AI coding engine",
    "developer/src/main/java/com/jarvis/developer/MainActivity.kt",
    "Gemini"
  ],
  [
    "Multi-file generation",
    "developer/src/main/java/com/jarvis/developer/ProjectGenerationWorkbench.kt",
    "class"
  ],
  [
    "GitHub integration",
    "developer/src/main/java/com/jarvis/developer/MainActivity.kt",
    "repository"
  ],
  [
    "Approved tasks",
    "developer/src/main/java/com/jarvis/developer/ApplicationApprovalWorkbench.kt",
    "approval"
  ],
  [
    "Build repair",
    "developer/src/main/java/com/jarvis/developer/MainActivity.kt",
    "build"
  ],
  [
    "APK build",
    "developer/build.gradle",
    "com.android.application"
  ],
  [
    "APK installation",
    "developer/src/main/AndroidManifest.xml",
    "REQUEST_INSTALL_PACKAGES"
  ],
  [
    "Self update",
    "developer/src/main/AndroidManifest.xml",
    "FileProvider"
  ],
  [
    "Rollback",
    "developer/src/main/java/com/jarvis/developer/MainActivity.kt",
    "rollback"
  ],
  [
    "Development memory",
    "developer/src/main/java/com/jarvis/developer/MainActivity.kt",
    "saveState"
  ],
  [
    "Resume jobs",
    "developer/src/main/java/com/jarvis/developer/MainActivity.kt",
    "developer_job_history"
  ],
  [
    "Parallel workstreams",
    "developer/src/main/java/com/jarvis/developer/MainActivity.kt",
    "project"
  ],
  [
    "UI preview",
    "developer/src/main/java/com/jarvis/developer/MainActivity.kt",
    "preview"
  ],
  [
    "Alternative suggestions",
    "developer/src/main/java/com/jarvis/developer/MainActivity.kt",
    "suggest"
  ],
  [
    "Approved designs",
    "developer/src/main/java/com/jarvis/developer/ApplicationApprovalWorkbench.kt",
    "approval"
  ],
  [
    "Projects and Tools UI",
    "developer/src/main/java/com/jarvis/developer/MainActivity.kt",
    "projects"
  ],
  [
    "Back and Close",
    "developer/src/main/java/com/jarvis/developer/MainActivity.kt",
    "navigateBack"
  ],
  [
    "Diagnostics dashboard",
    "developer/src/main/java/com/jarvis/developer/MainActivity.kt",
    "diagnostic"
  ],
  [
    "Model and key handling",
    "developer/src/main/java/com/jarvis/developer/MainActivity.kt",
    "refreshModelPicker"
  ],
  [
    "Terminal tools",
    "developer/src/main/java/com/jarvis/developer/MainActivity.kt",
    "terminal"
  ],
  [
    "Privilege integration",
    "developer/src/main/java/com/jarvis/developer/MainActivity.kt",
    "Shizuku"
  ],
  [
    "Personal/Developer separation",
    "settings.gradle",
    "include(':developer')"
  ],
  [
    "Verified release APK",
    "developer/build.gradle",
    "release"
  ],
  [
    "Publication and update path",
    "developer/src/main/AndroidManifest.xml",
    "updates"
  ]
]

def audit():
    results = []
    for index, (requirement, relative_path, marker) in enumerate(CHECKS, 1):
        path = ROOT / relative_path
        present = path.is_file()
        found = present and marker.casefold() in path.read_text(encoding="utf-8", errors="replace").casefold()
        results.append({
            "id": index, "requirement": requirement, "source": relative_path,
            "source_exists": present, "marker_found": bool(found),
            "status": "static_evidence_only" if found else "unverified",
            "runtime_verified": False, "apk_verified": False
        })
    return results

if __name__ == "__main__":
    report = audit()
    output = ROOT / "developer-requirements-evidence.json"
    output.write_text(json.dumps({"checks": report, "completed": 0,
        "warning": "Static source markers are not functional tests or completion evidence."},
        indent=2) + "\n", encoding="utf-8")
    for item in report:
        print(f"{item['id']:02} {item['status']:22} {item['requirement']}")
    print(f"Wrote {output}")
