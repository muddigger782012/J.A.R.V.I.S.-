#!/usr/bin/env python3
"""Fixed build-only controller. Never publishes, changes refs, or installs an APK.

Only the trusted controller copy of this file runs in the signing job. Candidate
source and its Gradle tasks run in a different job without signing credentials.
"""
import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import re
import selectors
import signal
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
import xml.etree.ElementTree as ET

REPO = "muddigger782012/J.A.R.V.I.S.-"
BRANCH = "feature/jarvis-developer"
PACKAGE = "com.jarvis.developer"
SHA = re.compile(r"[0-9a-f]{40}\Z")
HASH = re.compile(r"[0-9a-f]{64}\Z")
TASK_BRANCH = re.compile(r"feature/jarvis-developer-task/[A-Za-z0-9][A-Za-z0-9_-]{0,47}-[a-f0-9]{12}\Z")
PROTECTED = re.compile(r"agent|patch|github|repository|taskplan|buildfeedback|builddiagnostic|repairengine|repairworkbench|candidate|selfdevelopment|model|provider|transport|credential|secret|token|password|signing|keystore|updat|release|recovery|approval|publish|mainactivity|buildconfig|apikey|api_key|networksecurity", re.I)
EXECUTION = re.compile(r"\b(?:Runtime|ProcessBuilder|DexClassLoader|URLClassLoader|ScriptEngineManager|MethodHandles|ClassLoader|javaClass|getClass|exec|execve|spawn|load|loadLibrary|getDeclaredMethod|getDeclaredField|getDeclaredConstructor|getMethod|getField|getConstructor|forName|invoke|newInstance)\b|\b(?:java\s*\.\s*lang\s*\.\s*reflect|kotlin\s*\.\s*reflect|javax\s*\.\s*script)\b|::\s*class\b|\\u[0-9a-f]{4}", re.I)
KNOWN_CREDENTIAL = re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9_]{16,}|github_pat_[A-Za-z0-9_]{16,}|sk-[A-Za-z0-9_-]{16,}|AIza[A-Za-z0-9_-]{20,})\b|-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----")
NAMED_CREDENTIAL = re.compile(r"(?:api[_-]?key|token|password|secret|authorization)[\"']?\s*(?::\s*[A-Za-z?]+)?\s*[=:]\s*[\"'][^\"'\r\n]+[\"']", re.I)
PROTECTED_RESOURCE = re.compile(r"signing|keystore|update|release|recovery|credential|application[_-]?id|package[_-]?name|file[_-]?paths|network[_-]?security", re.I)
CODE_PATH = re.compile(r"developer/src/(?:main|test|androidTest)/(?:java|kotlin)/com/jarvis/developer/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+\.(?:kt|java)\Z")
RESOURCE_PATH = re.compile(r"developer/src/main/res/(?:layout|drawable|values|menu)(?:-[A-Za-z0-9_-]+)?/[A-Za-z0-9_]+\.xml\Z")
MAX_APK = 64 * 1024 * 1024
MAX_ZIP = MAX_APK + 1024 * 1024
ARTIFACT_NAMES = {"candidate": "Jarvis-Developer-unsigned-candidate", "recovery": "Jarvis-Developer-unsigned-recovery"}
INPUT_NAMES = {"candidate": {"candidate-unsigned.apk", "tests.json"}, "recovery": {"recovery-unsigned.apk"}}
CURRENT_STAGE = "startup"


class AuditFailure(Exception):
    """Only fixed, non-sensitive failure messages cross the CLI boundary."""


def require(condition, message):
    if not condition:
        raise AuditFailure(message)


def checkpoint(name):
    # Call sites supply fixed controller labels, never remote text or paths.
    global CURRENT_STAGE
    CURRENT_STAGE = name
    print("JARVIS_CANDIDATE_AUDIT_STAGE=" + name, flush=True)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON key")
        result[key] = value
    return result


def decode_json(data):
    require(len(data) <= 128_000, "JSON exceeds budget")
    try:
        return json.loads(data.decode("utf-8"), object_pairs_hook=unique_object)
    except (ValueError, UnicodeError):
        raise AuditFailure("Invalid bounded JSON") from None


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("ascii")


def write_json(path, value):
    data = encoded(value)
    require(len(data) <= 128_000, "JSON exceeds budget")
    Path(path).write_bytes(data)


def fixed_command(args, cwd=None, timeout=120, env=None):
    # Every caller constructs the executable and flags in trusted code, never a
    # repository command, shell string, candidate input, or downloaded script.
    process = None
    try:
        process = subprocess.Popen(args, cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
        output, total = bytearray(), 0
        deadline = time.monotonic() + timeout
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ, True)
            selector.register(process.stderr, selectors.EVENT_READ, False)
            while selector.get_map():
                remaining = deadline - time.monotonic()
                require(remaining > 0, "Fixed validation tool timed out")
                for key, _ in selector.select(min(remaining, 0.25)):
                    chunk = os.read(key.fileobj.fileno(), 4096)
                    if not chunk:
                        selector.unregister(key.fileobj)
                        key.fileobj.close()
                    else:
                        total += len(chunk)
                        require(total <= 256_000, "Validation output exceeds budget")
                        if key.data:
                            output.extend(chunk)
        require(process.wait(timeout=max(0.01, deadline - time.monotonic())) == 0, "Fixed validation tool failed")
        return output.decode("utf-8", errors="strict")
    except (OSError, subprocess.SubprocessError):
        raise AuditFailure("Fixed validation tool unavailable") from None
    finally:
        if process is not None:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            for pipe in (process.stdout, process.stderr):
                if pipe is not None:
                    pipe.close()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class GitHubRead:
    def __init__(self, token):
        self.token = token
        self.requests = 0
        self.opener = urllib.request.build_opener(NoRedirect)

    def get(self, path, limit=128_000, binary=False, missing=False):
        require(path.startswith(f"/repos/{REPO}/"), "Unexpected GitHub endpoint")
        self.requests += 1
        require(self.requests <= 24, "GitHub read budget exhausted")
        headers = {"Authorization": "Bearer " + self.token, "X-GitHub-Api-Version": "2022-11-28",
                   "Accept": "application/octet-stream" if binary else "application/vnd.github+json"}
        request = urllib.request.Request("https://api.github.com" + path, headers=headers, method="GET")
        try:
            response = self.opener.open(request, timeout=45)
        except urllib.error.HTTPError as error:
            if error.code == 404 and missing:
                return None
            if error.code in (301, 302, 303, 307, 308) and binary:
                location = error.headers.get("Location", "")
                parsed = urllib.parse.urlsplit(location)
                host = parsed.hostname or ""
                allowed = host in {"release-assets.githubusercontent.com", "objects.githubusercontent.com"} or re.fullmatch(r"productionresults[a-z0-9]{0,24}\.blob\.core\.windows\.net", host)
                require(parsed.scheme == "https" and allowed and parsed.port is None and parsed.username is None and parsed.password is None and not parsed.fragment, "Unrecognized download redirect")
                # Signed storage requests deliberately carry NO authorization.
                try:
                    response = self.opener.open(urllib.request.Request(location, method="GET"), timeout=45)
                except (urllib.error.URLError, ValueError):
                    raise AuditFailure("Signed download failed") from None
            else:
                raise AuditFailure("GitHub read failed HTTP " + str(int(error.code))) from None
        except (urllib.error.URLError, ValueError):
            raise AuditFailure("GitHub read failed") from None
        with response:
            require(response.status == 200, "Unexpected download response")
            data = response.read(limit + 1)
        require(0 < len(data) <= limit, "Download empty or exceeds budget")
        return data if binary else decode_json(data)


def apk_bytes(path):
    path = Path(path)
    require(path.is_file() and not path.is_symlink(), "Expected regular APK")
    require(0 < path.stat().st_size <= MAX_APK, "APK exceeds budget")
    data = path.read_bytes()
    # Validate archive names and expansion before handing bytes to Android tools.
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            entries = archive.infolist()
            require(0 < len(entries) <= 20_000, "APK entry budget exceeded")
            require(sum(item.file_size for item in entries) <= 256 * 1024 * 1024, "APK expansion exceeds budget")
            names = set()
            for item in entries:
                require(item.filename not in names, "Duplicate APK entry")
                names.add(item.filename)
                parts = item.filename.rstrip("/").split("/")
                require(not item.filename.startswith("/") and all(part not in ("", ".", "..") for part in parts) and "\\" not in item.filename and not (item.flag_bits & 1), "Unsafe APK entry")
                require(not stat.S_ISLNK(item.external_attr >> 16), "APK symlink forbidden")
    except (zipfile.BadZipFile, OSError):
        raise AuditFailure("Invalid APK archive") from None
    return data


def sdk_tool(name):
    require(name in {"aapt", "apksigner", "zipalign"}, "Unexpected SDK tool")
    return str(Path(os.environ["ANDROID_HOME"]) / "build-tools" / "34.0.0" / name)


def inspect_apk(path, source_sha, signed=True, command=fixed_command):
    data = apk_bytes(path)
    badging = command([sdk_tool("aapt"), "dump", "badging", str(path)])
    first = badging.splitlines()[0] if badging else ""
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)'", first)
    require(package is not None and package.group(1) == PACKAGE, "Unexpected APK package")
    version = int(package.group(2))
    require(0 < version <= 2_147_483_647, "Invalid APK version")
    require(SHA.fullmatch(source_sha) is not None, "Immutable source SHA required")
    signer = None
    if signed:
        certs = command([sdk_tool("apksigner"), "verify", "--verbose", "--print-certs", str(path)])
        digests = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", certs, re.M)
        require(len(digests) == 1, "Expected one verified APK signer")
        signer = digests[0].lower()
    return dict(sourceCommitSha=source_sha, packageName=PACKAGE, versionCode=version,
                apkSha256=hashlib.sha256(data).hexdigest(), apkBytes=len(data), signerSha256=signer)


def release_apk(api, tag, directory):
    release = api.get(f"/repos/{REPO}/releases/tags/{tag}", missing=True)
    if release is None:
        return None
    source = release.get("target_commitish", "")
    require(isinstance(source, str) and SHA.fullmatch(source) is not None, "Release lacks immutable source SHA")
    assets = [asset for asset in release.get("assets", []) if asset.get("name") == "Jarvis-Developer.apk"]
    require(len(assets) == 1, "Expected one fixed release APK")
    asset = assets[0]
    require(type(asset.get("id")) is int and asset["id"] > 0 and type(asset.get("size")) is int and 0 < asset["size"] <= MAX_APK, "Invalid release APK metadata")
    data = api.get(f"/repos/{REPO}/releases/assets/{asset['id']}", limit=MAX_APK, binary=True)
    require(len(data) == asset["size"], "Release APK size mismatch")
    digest = asset.get("digest")
    require(digest is None or digest == "sha256:" + hashlib.sha256(data).hexdigest(), "Release APK digest mismatch")
    target = Path(directory) / (tag + ".apk")
    target.write_bytes(data)
    return inspect_apk(target, source)


def same_backup(baseline, backup):
    return baseline is not None and backup is not None and all(baseline.get(key) == backup.get(key) for key in ("sourceCommitSha", "packageName", "versionCode", "apkSha256", "apkBytes", "signerSha256"))


def git(args, controller):
    return fixed_command(["git", *args], cwd=controller).strip()


def validate_source(controller, candidate_sha, candidate_branch, baseline_sha):
    require(SHA.fullmatch(candidate_sha) is not None, "Immutable candidate SHA required")
    require(candidate_branch == BRANCH or TASK_BRANCH.fullmatch(candidate_branch), "Isolated source branch required")
    require(not KNOWN_CREDENTIAL.search(candidate_branch), "Recognized credential in source branch forbidden")
    controller_sha = os.environ["GITHUB_SHA"]
    require(SHA.fullmatch(controller_sha) is not None and os.environ["GITHUB_REF"] == "refs/heads/" + BRANCH, "Trusted controller branch required")
    require(git(["rev-parse", "HEAD"], controller) == controller_sha, "Controller source mismatch")
    head = git(["rev-parse", "refs/remotes/origin/" + candidate_branch], controller)
    require(SHA.fullmatch(head) is not None, "Source branch missing")
    git(["merge-base", "--is-ancestor", candidate_sha, head], controller)
    git(["merge-base", "--is-ancestor", baseline_sha, candidate_sha], controller)
    git(["merge-base", "--is-ancestor", baseline_sha, controller_sha], controller)
    if candidate_sha == controller_sha:
        require(candidate_branch == BRANCH, "Controller source must use controller branch")
        return dict(baseCommitSha=controller_sha, changedPaths=[], scopeVerified=True, controlsUnchanged=True, branchIsolated=False)
    require(candidate_branch != BRANCH, "Self-development requires a sibling task branch")
    git(["merge-base", "--is-ancestor", controller_sha, candidate_sha], controller)
    changes = git(["diff", "--name-status", "--no-renames", controller_sha, candidate_sha], controller).splitlines()
    require(1 <= len(changes) <= 8, "Candidate change count exceeds scope")
    for change in changes:
        fields = change.split("\t")
        require(len(fields) == 2 and fields[0] in {"A", "M"}, "Candidate deletion or rename forbidden")
        status, path = fields
        require((CODE_PATH.fullmatch(path) or RESOURCE_PATH.fullmatch(path)) and not PROTECTED.search(path) and not KNOWN_CREDENTIAL.search(path), "Candidate changes a protected path")
        require(not ("/test/" in path or "/androidTest/" in path) or status == "A", "Existing baseline tests cannot be changed")
        mode = git(["ls-tree", candidate_sha, "--", path], controller).split(" ", 1)[0]
        require(mode == "100644", "Candidate file must be regular non-executable source")
        size = git(["cat-file", "-s", candidate_sha + ":" + path], controller)
        require(size.isdecimal() and 0 < int(size) <= 200_000, "Candidate file exceeds scope budget")
        content = git(["show", candidate_sha + ":" + path], controller)
        validate_content(path, content)
    return dict(baseCommitSha=controller_sha, changedPaths=sorted(change.split("\t")[1] for change in changes),
                scopeVerified=True, controlsUnchanged=True, branchIsolated=True)


def validate_content(path, content):
    # Defense in depth for directly selected task refs, which may not have been
    # created through Phase 2. Lexical checks are intentionally conservative;
    # they are not a proof that arbitrary allowed application code is harmless.
    require(not KNOWN_CREDENTIAL.search(content) and not NAMED_CREDENTIAL.search(content), "Recognized credential literal forbidden")
    require(not EXECUTION.search(content), "Process or dynamic execution forbidden")
    require(not any(ord(char) < 32 and char not in "\r\n\t" for char in content), "Binary source forbidden")
    if CODE_PATH.fullmatch(path):
        declaration = re.search(r"(?m)^\s*package\s+(com\.jarvis\.developer(?:\.[A-Za-z_][A-Za-z0-9_]*)*)\s*;?\s*$", content)
        require(declaration is not None, "Developer package declaration required")
        expected = path.split("/com/jarvis/developer/", 1)[1].rsplit("/", 1)
        expected_package = "com.jarvis.developer" + ("." + expected[0].replace("/", ".") if len(expected) == 2 else "")
        require(declaration.group(1) == expected_package, "Developer source package mismatch")
    else:
        require("<!DOCTYPE" not in content.upper() and "<!ENTITY" not in content.upper(), "XML declarations forbidden")
        try:
            root = ET.fromstring(content)
        except ET.ParseError:
            raise AuditFailure("Invalid resource XML") from None
        for child in root.iter():
            name = child.attrib.get("name", "")
            require(not PROTECTED_RESOURCE.search(name), "Protected resource value forbidden")


def previous_tag(api, baseline_sha):
    ref = api.get(f"/repos/{REPO}/git/ref/tags/developer-previous", missing=True)
    if ref is None:
        return False
    target = ref.get("object", {})
    for _ in range(3):
        if target.get("type") == "commit":
            return target.get("sha") == baseline_sha
        if target.get("type") != "tag" or not SHA.fullmatch(target.get("sha", "")):
            return False
        target = api.get(f"/repos/{REPO}/git/tags/{target['sha']}").get("object", {})
    return False


def resolve(args):
    root = Path(args.directory)
    root.mkdir(parents=True, exist_ok=True)
    api = GitHubRead(os.environ["GH_TOKEN"])
    baseline = release_apk(api, "developer-latest", root)
    require(baseline is not None, "Published baseline missing; evaluation cannot select recovery source")
    candidate = os.environ.get("CANDIDATE_SHA") or os.environ["GITHUB_SHA"]
    branch = os.environ.get("CANDIDATE_BRANCH") or BRANCH
    validate_source(args.controller, candidate, branch, baseline["sourceCommitSha"])
    version = max(baseline["versionCode"] + 1, 1_000_000 + int(os.environ["GITHUB_RUN_NUMBER"]) * 2)
    require(version + 1 <= 2_147_483_647, "Version budget exhausted")
    plan = dict(candidateCommitSha=candidate, candidateBranch=branch, baseline=baseline,
                candidateVersionCode=version, recoveryVersionCode=version + 1,
                controllerCommitSha=os.environ["GITHUB_SHA"], runId=int(os.environ["GITHUB_RUN_ID"]), runAttempt=int(os.environ["GITHUB_RUN_ATTEMPT"]))
    write_json(root / "plan.json", plan)
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        for name, value in (("candidate_sha", candidate), ("candidate_branch", branch), ("recovery_sha", baseline["sourceCommitSha"]), ("baseline_apk_sha256", baseline["apkSha256"]), ("candidate_version", version), ("recovery_version", version + 1)):
            output.write(f"{name}={value}\n")


def collect(args, kind):
    checkout = Path(args.checkout)
    output = Path(args.directory)
    output.mkdir(parents=True, exist_ok=True)
    # Check build/source state again after candidate code executed. This catches
    # ordinary build-side edits; it is not a hostile-code sandbox attestation.
    require(kind in INPUT_NAMES, "Unknown fixed build input")
    require(git(["rev-parse", "HEAD"], checkout) == os.environ[kind.upper() + "_SHA"], "Build checkout moved")
    require(not git(["status", "--porcelain", "--untracked-files=no"], checkout), "Build changed tracked source")
    if kind == "recovery":
        (output / "recovery-unsigned.apk").write_bytes(apk_bytes(checkout / "developer/build/outputs/apk/release/developer-release-unsigned.apk"))
        return
    files = sorted((checkout / "developer/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
    require(0 < len(files) <= 64 and sum(path.stat().st_size for path in files) <= 3_000_000, "Expected bounded JUnit evidence")
    counts = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
    for path in files:
        require(path.is_file() and not path.is_symlink(), "Regular JUnit evidence required")
        data = path.read_bytes()
        require(b"<!DOCTYPE" not in data.upper() and b"<!ENTITY" not in data.upper(), "JUnit declarations forbidden")
        suite = ET.fromstring(data)
        for name in counts:
            count = int(suite.attrib[name])
            require(0 <= count <= 10_000, "Invalid JUnit counts")
            counts[name] += count
        require(int(suite.attrib["tests"]) == len(suite.findall("testcase")), "JUnit case count mismatch")
    lint = checkout / "developer/build/reports/lint-results-debug.xml"
    require(lint.is_file() and not lint.is_symlink() and lint.stat().st_size <= 3_000_000, "Bounded lint evidence required")
    data = lint.read_bytes()
    require(b"<!DOCTYPE" not in data.upper() and b"<!ENTITY" not in data.upper(), "Lint declarations forbidden")
    issues = ET.fromstring(data).findall("issue")
    errors = sum(issue.attrib.get("severity") in ("Fatal", "Error") for issue in issues)
    warnings = sum(issue.attrib.get("severity") == "Warning" for issue in issues)
    tests = dict(passed=counts["tests"] > 0 and sum(counts[name] for name in ("failures", "errors", "skipped")) == 0 and errors == 0,
                 **counts, lintErrors=errors, lintWarnings=warnings)
    write_json(output / "tests.json", dict(sourceCommitSha=os.environ["CANDIDATE_SHA"], **tests))
    (output / "candidate-unsigned.apk").write_bytes(apk_bytes(checkout / "developer/build/outputs/apk/release/developer-release-unsigned.apk"))
    require(tests["passed"], "Candidate test or lint gate failed")


def extract_inputs(data, directory, kind):
    require(kind in INPUT_NAMES, "Unknown fixed build input")
    require(0 < len(data) <= MAX_ZIP, "Artifact ZIP exceeds budget")
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            entries = archive.infolist()
            require(len(entries) == len(INPUT_NAMES[kind]) and {item.filename for item in entries} == INPUT_NAMES[kind], "Unexpected artifact filenames")
            require(sum(item.file_size for item in entries) <= MAX_APK + 128_000, "Artifact expansion exceeds budget")
            for item in entries:
                require(not item.is_dir() and not stat.S_ISLNK(item.external_attr >> 16) and not (item.flag_bits & 1), "Unsafe artifact entry")
                limit = 128_000 if item.filename == "tests.json" else MAX_APK
                require(0 < item.file_size <= limit, "Artifact member exceeds budget")
                with archive.open(item) as source:
                    payload = source.read(limit + 1)
                require(len(payload) == item.file_size, "Artifact member size mismatch")
                target = Path(directory) / item.filename
                require(not target.exists(), "Artifact destination already exists")
                target.write_bytes(payload)
    except (zipfile.BadZipFile, OSError, RuntimeError):
        raise AuditFailure("Invalid bounded artifact ZIP") from None


def download_inputs(api, directory, identity=None):
    identity = os.environ if identity is None else identity
    run_id = int(identity["GITHUB_RUN_ID"])
    attempt = int(identity["GITHUB_RUN_ATTEMPT"])
    require(run_id > 0 and attempt > 0, "Invalid run identity")
    checkpoint("unsigned-run")
    run = api.get(f"/repos/{REPO}/actions/runs/{run_id}/attempts/{attempt}")
    require(run.get("id") == run_id and run.get("run_attempt") == attempt and run.get("head_sha") == identity["GITHUB_SHA"] and run.get("head_branch") == BRANCH and run.get("path") == ".github/workflows/evaluate-jarvis-developer.yml", "Unsigned run identity mismatch")
    seen = set()
    for kind in ("candidate", "recovery"):
        # These IDs and digests come directly from the separate trusted upload
        # step outputs through needs.*, never an untrusted artifact name search.
        raw_id = identity[kind.upper() + "_ARTIFACT_ID"]
        expected_digest = identity[kind.upper() + "_ARTIFACT_DIGEST"]
        require(re.fullmatch(r"[1-9][0-9]{0,18}", raw_id) is not None and HASH.fullmatch(expected_digest) is not None, "Missing trusted artifact output")
        artifact_id = int(raw_id)
        require(artifact_id not in seen, "Distinct unsigned artifacts required")
        seen.add(artifact_id)
        checkpoint(kind + "-artifact-metadata")
        item = api.get(f"/repos/{REPO}/actions/artifacts/{artifact_id}")
        require(type(item.get("id")) is int and item["id"] == artifact_id and item.get("name") == ARTIFACT_NAMES[kind] + "-attempt-" + str(attempt) and item.get("expired") is False, "Unexpected exact unsigned artifact")
        require(type(item.get("size_in_bytes")) is int and 0 < item["size_in_bytes"] <= MAX_ZIP, "Invalid artifact metadata")
        workflow = item.get("workflow_run", {})
        require(workflow.get("id") == run_id and workflow.get("head_sha") == identity["GITHUB_SHA"] and workflow.get("head_branch") == BRANCH, "Artifact source mismatch")
        require(item.get("digest") == "sha256:" + expected_digest, "Artifact digest metadata mismatch")
        checkpoint(kind + "-artifact-download")
        data = api.get(f"/repos/{REPO}/actions/artifacts/{artifact_id}/zip", limit=MAX_ZIP, binary=True)
        require(hashlib.sha256(data).hexdigest() == expected_digest, "Artifact digest mismatch")
        checkpoint(kind + "-artifact-extract")
        extract_inputs(data, directory, kind)


def probe_unsigned(args):
    # Explicit historical identity: never rewrite the executing controller's
    # GITHUB_SHA or run environment, and never inspect/execute/sign APK contents.
    require(SHA.fullmatch(args.source_sha) is not None, "Immutable probe source SHA required")
    output = Path(args.directory)
    output.mkdir(parents=True, exist_ok=False)
    identity = dict(GITHUB_RUN_ID=str(args.run_id), GITHUB_RUN_ATTEMPT=str(args.run_attempt), GITHUB_SHA=args.source_sha,
                    CANDIDATE_ARTIFACT_ID=args.candidate_artifact_id, CANDIDATE_ARTIFACT_DIGEST=args.candidate_artifact_digest,
                    RECOVERY_ARTIFACT_ID=args.recovery_artifact_id, RECOVERY_ARTIFACT_DIGEST=args.recovery_artifact_digest)
    download_inputs(GitHubRead(os.environ["GH_TOKEN"]), output, identity)
    print("JARVIS_CANDIDATE_UNSIGNED_PROBE=VERIFIED", flush=True)


def evaluate(candidate, baseline, backup, rebuild, tests, source):
    reasons = []
    if baseline is None:
        reasons.append("BASELINE_MISSING")
    if backup is None:
        reasons.append("BACKUP_MISSING")
    elif not backup.get("verified") or not same_backup(baseline, backup):
        reasons.append("BACKUP_UNVERIFIED")
    if rebuild is None:
        reasons.append("RECOVERY_REBUILD_MISSING")
    elif not rebuild.get("verified") or baseline is None or rebuild["sourceCommitSha"] != baseline["sourceCommitSha"] or rebuild["packageName"] != PACKAGE or rebuild["signerSha256"] != baseline["signerSha256"] or rebuild["versionCode"] <= candidate["versionCode"]:
        reasons.append("RECOVERY_REBUILD_UNVERIFIED")
    if baseline is None or candidate["packageName"] != PACKAGE or candidate["signerSha256"] != baseline["signerSha256"] or candidate["versionCode"] <= baseline["versionCode"] or not all(source.get(key) for key in ("scopeVerified", "controlsUnchanged", "branchIsolated")):
        reasons.append("CANDIDATE_UNVERIFIED")
    if not tests.get("passed") or tests.get("tests", 0) <= 0 or any(tests.get(key) != 0 for key in ("failures", "errors", "skipped", "lintErrors")):
        reasons.append("TESTS_INCOMPLETE")
    return dict(schemaVersion=1, status="BLOCKED" if reasons else "READY", candidate=candidate, tests=tests, source=source,
                recovery=dict(baseline=baseline, backup=backup, rebuild=rebuild, missingReasons=reasons))


def sign_apk(source, target, key, alias, command=fixed_command):
    aligned = Path(str(target) + ".aligned")
    command([sdk_tool("zipalign"), "-f", "4", str(source), str(aligned)])
    command([sdk_tool("apksigner"), "sign", "--ks", str(key), "--ks-key-alias", alias,
             "--ks-pass", "env:STORE_PASS", "--key-pass", "env:KEY_PASS", "--out", str(target), str(aligned)])
    aligned.unlink(missing_ok=True)


def sign(args):
    output = Path(args.directory)
    output.mkdir(parents=True, exist_ok=True)
    checkpoint("controller-source")
    require(os.environ["GITHUB_REF"] == "refs/heads/" + BRANCH and git(["rev-parse", "HEAD"], args.controller) == os.environ["GITHUB_SHA"], "Trusted controller source mismatch")
    api = GitHubRead(os.environ["GH_TOKEN"])
    inputs = output / "inputs"
    inputs.mkdir()
    download_inputs(api, inputs)
    checkpoint("published-baseline")
    baseline = release_apk(api, "developer-latest", output)
    require(baseline is not None, "Published baseline unavailable")
    require(baseline["sourceCommitSha"] == os.environ["RECOVERY_SHA"] and baseline["apkSha256"] == os.environ["BASELINE_APK_SHA256"], "Published baseline changed during evaluation")
    candidate_sha = os.environ["CANDIDATE_SHA"]
    candidate_branch = os.environ["CANDIDATE_BRANCH"]
    checkpoint("source-scope")
    source = validate_source(args.controller, candidate_sha, candidate_branch, baseline["sourceCommitSha"])
    backup = None
    checkpoint("previous-backup")
    try:
        prior = release_apk(api, "developer-previous", output)
        if prior is not None:
            backup = dict(**prior, verified=same_backup(baseline, prior) and previous_tag(api, baseline["sourceCommitSha"]))
    except AuditFailure:
        # Missing/unverifiable backup is retained as BLOCKED, never converted to pass.
        backup = dict(**baseline, verified=False)
    checkpoint("unsigned-apk-identities")
    candidate_unsigned = inspect_apk(inputs / "candidate-unsigned.apk", candidate_sha, signed=False)
    recovery_unsigned = inspect_apk(inputs / "recovery-unsigned.apk", baseline["sourceCommitSha"], signed=False)
    require(candidate_unsigned["versionCode"] == int(os.environ["CANDIDATE_VERSION"]) and recovery_unsigned["versionCode"] == int(os.environ["RECOVERY_VERSION"]), "Unsigned version mismatch")
    require(recovery_unsigned["versionCode"] > candidate_unsigned["versionCode"] > baseline["versionCode"], "Higher-version recovery required")
    checkpoint("candidate-test-counts")
    test_data = decode_json((inputs / "tests.json").read_bytes())
    require(set(test_data) == {"sourceCommitSha", "passed", "tests", "failures", "errors", "skipped", "lintErrors", "lintWarnings"} and test_data.pop("sourceCommitSha") == candidate_sha, "Candidate test source mismatch")
    require(type(test_data["passed"]) is bool and all(type(test_data[key]) is int and 0 <= test_data[key] <= 10_000 for key in test_data if key != "passed"), "Invalid test counts")
    key = output / "permanent-signing-key.jks"
    try:
        checkpoint("permanent-signing-key")
        require(all(os.environ.get(name) for name in ("KEY_B64", "STORE_PASS", "KEY_PASS", "KEY_ALIAS")), "Permanent signing configuration unavailable")
        try:
            material = base64.b64decode(os.environ["KEY_B64"], validate=True)
        except ValueError:
            raise AuditFailure("Permanent signing key encoding invalid") from None
        require(0 < len(material) <= 128_000, "Signing key size invalid")
        descriptor = os.open(key, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(material)
        del material
        for label in ("candidate", "recovery"):
            checkpoint(label + "-fixed-signing")
            sign_apk(inputs / (label + "-unsigned.apk"), output / (label + ".apk"), key, os.environ["KEY_ALIAS"])
    finally:
        key.unlink(missing_ok=True)
    checkpoint("signed-apk-identities")
    candidate = inspect_apk(output / "candidate.apk", candidate_sha)
    candidate.pop("sourceCommitSha")
    candidate.update(commitSha=candidate_sha, branch=candidate_branch, controllerCommitSha=os.environ["GITHUB_SHA"],
                     controllerBranch=BRANCH, runId=int(os.environ["GITHUB_RUN_ID"]), runAttempt=int(os.environ["GITHUB_RUN_ATTEMPT"]))
    rebuild = dict(**inspect_apk(output / "recovery.apk", baseline["sourceCommitSha"]), verified=True)
    checkpoint("evaluation-evidence")
    evidence = evaluate(candidate, baseline, backup, rebuild, test_data, source)
    data = encoded(evidence)
    require(len(data) <= 32_000, "Candidate evidence exceeds budget")
    (output / "candidate-evidence.json").write_bytes(data)
    print("JARVIS_CANDIDATE_EVIDENCE_SHA256=" + hashlib.sha256(data).hexdigest())
    print("JARVIS_CANDIDATE_EVIDENCE=" + data.decode("ascii"))
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
        summary.write("### Build-only candidate evaluation\n\n" + evidence["status"] + "\n\n")
        summary.write("Candidate: `" + candidate_sha + "`\n\n")
        summary.write("Recovery safeguards: " + (", ".join(evidence["recovery"]["missingReasons"]) or "verified") + "\n\nNo publication or installation is performed by this workflow.\n")
    # BLOCKED is a useful successful audit result, never permission to promote.


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="action", required=True)
    for name in ("resolve", "sign"):
        command = sub.add_parser(name)
        command.add_argument("--controller", required=True)
        command.add_argument("--directory", required=True)
    for name in ("collect-candidate", "collect-recovery"):
        command = sub.add_parser(name)
        command.add_argument("--checkout", required=True)
        command.add_argument("--directory", required=True)
    command = sub.add_parser("probe-unsigned")
    command.add_argument("--directory", required=True)
    command.add_argument("--run-id", required=True, type=int)
    command.add_argument("--run-attempt", required=True, type=int)
    command.add_argument("--source-sha", required=True)
    for kind in ("candidate", "recovery"):
        command.add_argument("--" + kind + "-artifact-id", required=True)
        command.add_argument("--" + kind + "-artifact-digest", required=True)
    args = parser.parse_args()
    try:
        if args.action.startswith("collect-"):
            collect(args, args.action.removeprefix("collect-"))
        else:
            {"resolve": resolve, "sign": sign, "probe-unsigned": probe_unsigned}[args.action](args)
    except AuditFailure as error:
        # AuditFailure messages are fixed controller constants (or numeric HTTP
        # status); they never contain tool stderr, paths, URLs or remote bodies.
        print("Candidate validation stopped: stage=" + CURRENT_STAGE + "; " + str(error), file=sys.stderr)
        return 1
    except (KeyError, ValueError, UnicodeError, OSError, ET.ParseError):
        # No traceback, tool stderr, token, signed redirect, key path or raw API body.
        print("Candidate validation stopped: stage=" + CURRENT_STAGE + "; fixed audit check failed.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
