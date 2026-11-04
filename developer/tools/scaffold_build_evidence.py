"""Bounded source and build evidence for isolated Phase 6 preview artifacts.

This tool never installs, publishes or invokes generated build commands. The
workflow invokes fixed platform commands only after source verification passes.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import zipfile
import xml.etree.ElementTree as ET


PLATFORMS = ("android", "backend", "windows")
PACKAGE = "io.example.focuscounter"
TEMPLATE_VERSION = "jarvis-starter-v1"
FIXTURES = {
    "starter": (PLATFORMS, TEMPLATE_VERSION, PACKAGE),
    "checklist": (("android",), "jarvis-checklist-v1", "io.example.offlinechecklist"),
}
MAX_FILE = 256_000
MAX_SOURCE = 2_000_000


def require(condition, message):
    if not condition:
        raise ValueError(message)


def bounded_bytes(path, limit):
    require(path.is_file() and not path.is_symlink(), "Expected regular evidence file")
    require(0 < path.stat().st_size <= limit, "Evidence file exceeds byte budget")
    return path.read_bytes()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def relative_path(value):
    require(isinstance(value, str) and 0 < len(value) <= 180, "Invalid source path")
    require(re.fullmatch(r"[A-Za-z0-9_.\-/]+", value) is not None, "Invalid source path")
    parts = value.split("/")
    require(all(part and part not in (".", "..") for part in parts), "Unsafe source path")
    path = PurePosixPath(value)
    require(not path.is_absolute(), "Unsafe source path")
    return path


def verify_sources(root, source_sha, platform, strict_tree=True, fixture="starter"):
    require(re.fullmatch(r"[0-9a-f]{40}", source_sha or "") is not None, "Immutable source SHA required")
    require(fixture in FIXTURES, "Unsupported fixture")
    platforms, template, _ = FIXTURES[fixture]
    require(platform in platforms, "Unsupported platform")
    raw = bounded_bytes(root / "evidence.json", 64_000)
    evidence = json.loads(raw)
    require(evidence["schemaVersion"] == 1, "Unsupported evidence schema")
    require(evidence["sourceCommitSha"] == source_sha, "Scaffold source SHA mismatch")
    require(evidence["templateVersion"] == template, "Unexpected scaffold template version")
    records = evidence["platforms"]
    require(isinstance(records, list) and len(records) == len(platforms), "Unexpected platform record count")
    require(sorted(item["platform"] for item in records) == sorted(platforms), "Platform identity mismatch")
    record = next(item for item in records if item["platform"] == platform)
    require(re.fullmatch(r"[0-9a-f]{64}", record["fingerprint"]) is not None, "Invalid plan fingerprint")
    files = record["files"]
    require(isinstance(files, list) and 0 < len(files) <= 64, "Source file count exceeds budget")
    paths, total, contents = set(), 0, {}
    project = root / platform
    for item in files:
        path = str(relative_path(item["path"]))
        require(path not in paths, "Duplicate source path")
        paths.add(path)
        candidate = project / path
        require(not any(part.is_symlink() for part in [candidate, *candidate.parents]), "Source symlink rejected")
        data = bounded_bytes(candidate, MAX_FILE)
        require(type(item["bytes"]) is int and len(data) == item["bytes"], "Source byte count mismatch")
        require(digest(data) == item["sha256"], "Source digest mismatch")
        total += len(data)
        contents[path] = data
    require(total <= MAX_SOURCE, "Project source exceeds byte budget")
    if strict_tree:
        actual = set()
        for entry in project.rglob("*"):
            require(not entry.is_symlink(), "Source symlink rejected")
            if entry.is_file():
                actual.add(entry.relative_to(project).as_posix())
        require(actual == paths, "Unexpected generated source file")
    archive_path = str(relative_path(record["zipPath"]))
    require(archive_path == platform + ".zip", "Unexpected archive path")
    archive = bounded_bytes(root / archive_path, MAX_SOURCE)
    require(digest(archive) == record["zipSha256"], "Archive digest mismatch")
    with zipfile.ZipFile(root / archive_path) as zipped:
        entries = zipped.infolist()
        require(len(entries) == len(paths), "Archive member count mismatch")
        require({entry.filename for entry in entries} == paths, "Archive member paths mismatch")
        for entry in entries:
            require(entry.file_size <= MAX_FILE and entry.file_size == len(contents[entry.filename]), "Archive member size mismatch")
            require(zipped.read(entry) == contents[entry.filename], "Archive source bytes mismatch")
    return dict(sourceCommitSha=source_sha, platform=platform, templateVersion=template,
                fingerprint=record["fingerprint"], sourceEvidenceSha256=digest(raw),
                zipSha256=record["zipSha256"], files=files)


def junit_records(directory):
    paths = sorted(directory.glob("TEST-*.xml"))
    require(0 < len(paths) <= 20, "Expected platform JUnit results")
    totals = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
    reports = []
    for path in paths:
        data = bounded_bytes(path, 256_000)
        suite = ET.fromstring(data)
        counts = {key: int(suite.attrib[key]) for key in totals}
        require(all(value >= 0 for value in counts.values()), "Invalid JUnit counts")
        cases = suite.findall("testcase")
        require(counts["tests"] == len(cases), "JUnit count mismatch")
        for count, child in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
            require(counts[count] == sum(case.find(child) is not None for case in cases), "JUnit outcome count mismatch")
        for key, value in counts.items():
            totals[key] += value
        reports.append(dict(name=path.name, sha256=digest(data), **counts))
    require(totals["tests"] > 0 and not any(totals[key] for key in ("failures", "errors", "skipped")), "Platform JUnit checks did not all pass")
    return dict(totals=totals, reports=reports)


def checked_output(argv):
    result = subprocess.run(argv, check=True, capture_output=True, timeout=60)
    require(len(result.stdout) <= 128_000 and len(result.stderr) <= 128_000, "Tool output exceeds budget")
    return result.stdout.decode("utf-8")


def apk_record(project, package=PACKAGE, android_test=False):
    relative = ("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" if android_test
                else "app/build/outputs/apk/debug/app-debug.apk")
    path = project / relative
    data = bounded_bytes(path, 64_000_000)
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/34.0.0"
    badging = checked_output([str(tools / "aapt"), "dump", "badging", str(path)])
    require(re.search(r"^package: name='" + re.escape(package) + "'", badging, re.M) is not None, "Unexpected preview APK package")
    signatures = checked_output([str(tools / "apksigner"), "verify", "--print-certs", str(path)])
    certs = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([a-f0-9]{64})$", signatures, re.M)
    require(len(certs) == 1, "Expected one verified debug signer")
    return dict(packageName=package, bytes=len(data), apkSha256=digest(data), signerSha256=certs[0], signing="ephemeral-debug")


def verify_checklist_build(root, source_sha):
    """Recheck source, unit results, both APKs and their shared signer before a disposable install."""
    source = verify_sources(root, source_sha, "android", strict_tree=False, fixture="checklist")
    recorded = json.loads(bounded_bytes(root / "android-build-evidence.json", 64_000))
    require(recorded.get("schemaVersion") == 1 and recorded.get("installed") is False and
            recorded.get("published") is False, "Unexpected build evidence state")
    for key, value in source.items():
        require(recorded.get(key) == value, "Build/source evidence mismatch")
    project = root / "android"
    require(junit_records(project / "app/build/test-results/testDebugUnitTest") == recorded.get("unitTests"),
            "Recorded unit results changed")
    package = FIXTURES["checklist"][2]
    app = apk_record(project, package)
    tests = apk_record(project, package + ".test", android_test=True)
    require(recorded.get("apk") == app and recorded.get("deviceTestApk") == tests, "Build/APK evidence mismatch")
    require(app["signerSha256"] == tests["signerSha256"], "App and instrumentation signer mismatch")
    return recorded


def backend_tests(path):
    data = bounded_bytes(path, 128_000)
    text = data.decode("utf-8")
    counts = re.findall(r"^Ran ([0-9]+) tests? in [0-9.]+s$", text, re.M)
    require(len(counts) == 1 and int(counts[0]) > 0, "Backend unittest result missing")
    require(re.search(r"^OK$", text, re.M) is not None, "Backend unittest did not pass")
    require(re.search(r"^(?:FAIL|ERROR|FAILED|OK \()", text, re.M) is None, "Backend unittest failure or skip")
    return dict(tests=int(counts[0]), failures=0, skipped=0, logSha256=digest(data))


def windows_smoke(project, path):
    data = bounded_bytes(path, 128_000)
    matches = [match.group(1) for line in data.decode("utf-8").splitlines()
               if (match := re.fullmatch(r"JARVIS_PHASE6_WINDOWS_SMOKE=(\{.+\})", line))]
    require(len(matches) == 1, "Expected one Windows smoke result")
    result = json.loads(matches[0])
    require(result.get("passed") is True and result.get("checks") == 6 and
            result.get("apartmentState") == "STA" and all(result.get(key) is True for key in
            ("titleMatches", "specificationMatches", "initialStateMatches", "firstActionMatches", "secondActionMatches")),
            "Windows smoke failed or incomplete")
    files = []
    directory = project / "app/bin/Release/net8.0-windows"
    for name in ("StarterApp.exe", "StarterApp.dll"):
        content = bounded_bytes(directory / name, 16_000_000)
        files.append(dict(path=name, bytes=len(content), sha256=digest(content)))
    return dict(result=result, logSha256=digest(data), binaries=files)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("verify", "record", "verify-device"))
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--platform", choices=PLATFORMS, required=True)
    parser.add_argument("--fixture", choices=tuple(FIXTURES), default="starter")
    args = parser.parse_args(argv)
    if args.action == "verify-device":
        require(args.fixture == "checklist" and args.platform == "android", "Unsupported device fixture")
        evidence = verify_checklist_build(args.root, os.environ.get("GITHUB_SHA", ""))
        print("JARVIS_PHASE6_CHECKLIST_PREINSTALL_VERIFIED=" + json.dumps(evidence, sort_keys=True, separators=(",", ":")))
        return
    evidence = verify_sources(args.root, os.environ.get("GITHUB_SHA", ""), args.platform,
                              strict_tree=args.action == "verify", fixture=args.fixture)
    evidence.update(schemaVersion=1, installed=False, published=False,
                    runId=os.environ.get("GITHUB_RUN_ID"), runAttempt=os.environ.get("GITHUB_RUN_ATTEMPT"))
    if args.action == "record":
        project = args.root / args.platform
        if args.platform == "android":
            evidence["unitTests"] = junit_records(project / "app/build/test-results/testDebugUnitTest")
            package = FIXTURES[args.fixture][2]
            evidence["apk"] = apk_record(project, package)
            if args.fixture == "checklist":
                evidence["deviceTestApk"] = apk_record(project, package + ".test", android_test=True)
                require(evidence["apk"]["signerSha256"] == evidence["deviceTestApk"]["signerSha256"],
                        "App and instrumentation signer mismatch")
        elif args.platform == "backend":
            evidence["unitTests"] = backend_tests(args.root / "backend-tests.log")
        else:
            evidence["smoke"] = windows_smoke(project, args.root / "windows-smoke.log")
        raw = json.dumps(evidence, sort_keys=True, separators=(",", ":")).encode()
        require(len(raw) <= 64_000, "Build evidence exceeds log budget")
        (args.root / (args.platform + "-build-evidence.json")).write_bytes(raw)
        print("JARVIS_PHASE6_" + args.platform.upper() + "_BUILD_SHA256=" + digest(raw))
        print("JARVIS_PHASE6_" + args.platform.upper() + "_BUILD=" + raw.decode())
    else:
        print("JARVIS_PHASE6_" + args.platform.upper() + "_SOURCES_VERIFIED=" + json.dumps(evidence, sort_keys=True, separators=(",", ":")))


if __name__ == "__main__":
    main()
