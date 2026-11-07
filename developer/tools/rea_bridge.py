#!/usr/bin/env python3
"""Isolated HTTPS APK-to-REA CLI bridge for J.A.R.V.I.S. Developer.

Operator-owned host only: requires Python 3, a verified rea-agents installation,
JDK and REA's pinned JADX engine. No Android app code executes in this process.
The APK is never executed; only static REA inspection is requested.
"""
import hashlib
import io
import hmac
import http.server
import json
import os
from pathlib import Path
import re
import ssl
import subprocess
import tempfile
import threading
import zipfile

MAX_APK = 24 * 1024 * 1024
MAX_REPORT = 1024 * 1024
MAX_EXCERPT = 30000
MAX_ARCHIVE_ENTRIES = 20000
MAX_UNCOMPRESSED = 768 * 1024 * 1024
MAX_MEMBER = 384 * 1024 * 1024
HEX = re.compile(r"^[0-9a-f]{64}$")
SCHEMA = "jarvis.rea.apk.v1"
LOCK = threading.BoundedSemaphore(1)


class BridgeError(Exception):
    def __init__(self, code, message, status=400):
        super().__init__(message)
        self.code, self.status = code, status


def verify_token(received, expected):
    if not expected or len(expected) < 24:
        return False
    return hmac.compare_digest(received, "Bearer " + expected)


def rea_launcher(rea_command="rea", rea_entry=None):
    """Fixed executable and optional absolute Node.js entry, never a shell."""
    if rea_entry:
        entry = Path(rea_entry)
        if not entry.is_absolute() or not entry.is_file():
            raise BridgeError("REA_CONFIGURATION", "REA entry must be an existing absolute JS path", 503)
        return [rea_command, str(entry)]
    if os.name == "nt":
        raise BridgeError("REA_CONFIGURATION", "Windows requires a Node executable and JARVIS_REA_ENTRY", 503)
    return [rea_command]


def health_status(rea_command="rea", rea_entry=None):
    """Preflight only. Doctor success does not prove a specific APK can be decompiled."""
    try:
        launcher = rea_launcher(rea_command, rea_entry)
        with tempfile.TemporaryDirectory(prefix="jarvis-rea-doctor-") as root:
            result = subprocess.run(launcher + ["doctor", "--provider", "jadx", "--json"],
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                    cwd=root, timeout=30, check=False)
        return "DOCTOR_PASSED" if result.returncode == 0 else "DOCTOR_FAILED"
    except (OSError, BridgeError, subprocess.TimeoutExpired):
        return "DOCTOR_FAILED"



def validate_apk_archive(data):
    """Inspect untrusted ZIP metadata before the fixed JADX analysis command.

    These are archive declarations, not verified extraction or a safety verdict.
    No archive member is extracted or executed by the preflight.
    """
    try:
        with zipfile.ZipFile(io.BytesIO(data), "r") as archive:
            entries = archive.infolist()
            if not entries or len(entries) > MAX_ARCHIVE_ENTRIES:
                raise BridgeError("APK_ARCHIVE", "APK ZIP entry count exceeds the preflight limit")
            seen = set()
            total = 0
            manifest = False
            for member in entries:
                name = member.filename
                if (not name or "\\" in name or "\x00" in name or
                        name.startswith("/") or re.match(r"^[A-Za-z]:", name) or
                        any(p in (".", "..") for p in name.split("/"))):
                    raise BridgeError("APK_ARCHIVE", "APK has an unsafe ZIP entry name")
                if name in seen:
                    raise BridgeError("APK_ARCHIVE", "APK contains duplicate ZIP entry names")
                seen.add(name)
                if member.flag_bits & 1:
                    raise BridgeError("APK_ARCHIVE", "Encrypted ZIP members are not supported")
                if (member.create_system == 3 and
                        ((member.external_attr >> 16) & 0o170000) == 0o120000):
                    raise BridgeError("APK_ARCHIVE", "APK contains a symbolic link")
                if member.is_dir():
                    continue
                if member.file_size > MAX_MEMBER or total + member.file_size > MAX_UNCOMPRESSED:
                    raise BridgeError("APK_ARCHIVE", "APK declares excessive uncompressed data")
                if member.file_size > 1024 * 1024 and (
                        member.file_size > 500 * max(1, member.compress_size)):
                    raise BridgeError("APK_ARCHIVE", "APK declares an excessive expansion ratio")
                total += member.file_size
                if name == "AndroidManifest.xml":
                    manifest = True
            if not manifest:
                raise BridgeError("APK_ARCHIVE", "APK has no AndroidManifest.xml entry")
            return len(entries), total
    except (zipfile.BadZipFile, zipfile.LargeZipFile, EOFError, OSError, ValueError):
        raise BridgeError("APK_ARCHIVE", "APK is not a valid bounded ZIP archive") from None



def summarize_apk_evidence(normalized):
    """Project declared REA findings to bounded portable chat fields.

    The upstream CLI summary may wrap the APK report in a summary view.
    Unknown/malformed facts are omitted, not inferred or marked verified.
    """
    if not isinstance(normalized, dict):
        return None
    source = normalized.get("summary", normalized)
    if not isinstance(source, dict) or not any(key in source for key in (
            "package_name", "version_name", "permissions",
            "class_count", "resource_count", "manifest", "components")):
        return None

    def clean(value, size=240):
        if (not isinstance(value, str) or not 1 <= len(value) <= size or
                any(ord(char) < 32 or ord(char) == 127 for char in value)):
            return None
        return value

    def sdk(value):
        if type(value) is int and 0 <= value <= 999:
            return str(value)
        if isinstance(value, str) and re.fullmatch(r"[0-9]{1,3}", value):
            return value
        return None

    def count(value):
        if type(value) is int and 0 <= value <= 10000000:
            return value
        return None

    raw_permissions = source.get("permissions")
    names = []
    seen = set()
    if isinstance(raw_permissions, list):
        for candidate in raw_permissions[:20000]:
            item = clean(candidate, 180)
            if item and item not in seen:
                names.append(item)
                seen.add(item)
    manifest = source.get("manifest")
    manifest_status = manifest.get("status") if isinstance(manifest, dict) else None
    if manifest_status not in ("complete", "partial", "unavailable", "unknown"):
        manifest_status = "not_reported"
    components = source.get("components")
    component_status = components.get("status") if isinstance(components, dict) else None
    if component_status not in ("complete", "partial", "unavailable", "unknown"):
        component_status = "not_reported"
    component_items = components.get("items") if isinstance(components, dict) else None
    component_count = count(len(component_items)) if isinstance(component_items, list) else None
    return {
        "package_name": clean(source.get("package_name")),
        "version_name": clean(source.get("version_name"), 120),
        "min_sdk": sdk(source.get("min_sdk")),
        "target_sdk": sdk(source.get("target_sdk")),
        "class_count": count(source.get("class_count")),
        "resource_count": count(source.get("resource_count")),
        "permissions": names[:32],
        "permission_count": len(names),
        "manifest_status": manifest_status,
        "component_status": component_status,
        "component_count": component_count,
    }


def analyze_apk(data, claimed_sha, rea_command="rea", rea_entry=None):
    if len(data) < 4 or len(data) > MAX_APK or data[:4] not in (
        b"PK\x03\x04", b"PK\x05\x06", b"PK\x07\x08"
    ):
        raise BridgeError("NOT_APK", "Expected a bounded ZIP-format Android APK")
    sha = hashlib.sha256(data).hexdigest()
    if not HEX.fullmatch(claimed_sha) or not hmac.compare_digest(sha, claimed_sha):
        raise BridgeError("HASH_MISMATCH", "APK SHA-256 did not match the submitted bytes")
    archive_info = validate_apk_archive(data)
    with tempfile.TemporaryDirectory(prefix="jarvis-rea-") as root:
        apk = Path(root, "selected.apk")
        apk.write_bytes(data)
        os.chmod(apk, 0o600)
        full = Path(root, "full-evidence.json")
        out = Path(root, "stdout.json")
        err = Path(root, "stderr.log")
        # Argument vector only; no shell or source-code execution. In a
        # private per-job directory, nothing is shared with other analyses.
        launcher = rea_launcher(rea_command, rea_entry)
        cmd = launcher + ["inspect-android-package", str(apk),
               "--detail", "summary", "--evidence_output", str(full), "--json"]
        try:
            with out.open("wb") as stdout, err.open("wb") as stderr:
                proc = subprocess.run(cmd, stdout=stdout, stderr=stderr,
                                      cwd=root, timeout=180, check=False)
        except subprocess.TimeoutExpired:
            raise BridgeError("REA_TIMEOUT", "REA exceeded the isolated analysis deadline", 504)
        except OSError:
            raise BridgeError("REA_UNAVAILABLE", "Install and configure rea-agents and JADX on the bridge host", 503)
        if proc.returncode != 0:
            raise BridgeError("REA_FAILED", "REA inspection failed; check REA/JADX on the host", 502)
        if out.stat().st_size == 0 or out.stat().st_size > MAX_REPORT:
            raise BridgeError("REA_REPORT", "REA returned no bounded summary; evidence not accepted", 502)
        raw = out.read_bytes()
        try:
            parsed = json.loads(raw)
        except (ValueError, UnicodeDecodeError):
            raise BridgeError("REA_REPORT", "REA returned malformed JSON", 502)
        if not isinstance(parsed, dict) or not isinstance(parsed.get("normalized_result"), dict) or (
            parsed.get("ok") is False or parsed.get("success") is False or
            parsed.get("error") or parsed.get("isError")
        ):
            raise BridgeError("REA_REPORT", "REA returned a failed analysis envelope", 502)
        # Keep a cryptographic identity for the *complete* original CLI output,
        # even when Android only receives a bounded excerpt. No false claims of
        # complete extraction, executable authenticity, or dynamic behavior.
        excerpt = raw.decode("utf-8")
        truncated = len(excerpt) > MAX_EXCERPT
        return {
            "schema": SCHEMA, "state": "ANALYZED",
            "apk_sha256": sha, "evidence_sha256": hashlib.sha256(raw).hexdigest(),
            "excerpt": excerpt[:MAX_EXCERPT],
            "archive_entries": archive_info[0],
            "archive_uncompressed_bytes": archive_info[1],
            "summary": summarize_apk_evidence(parsed["normalized_result"]),
            "truncated": truncated,
            "scope": "static-reverse-engineering-only",
            "engine": "REA inspect-android-package (JADX)",
            "limitations": "Static evidence; runtime behavior, authenticity and completeness not proven."
        }


CLASS_QUERY = re.compile(r"^[A-Za-z0-9_.$]{2,80}$")
CLASS_SCHEMA = "jarvis.rea.classes.v1"
MAX_CLASS_MATCHES = 40


def validate_class_query(query):
    if not isinstance(query, str) or not CLASS_QUERY.fullmatch(query):
        raise BridgeError("CLASS_QUERY", "Use a class substring of 2 to 80 ASCII characters")
    return query


def search_apk_classes(data, claimed_sha, query, rea_command="rea", rea_entry=None):
    """Read-only, hash-bound class search with a fixed REA/JADX command."""
    query = validate_class_query(query)
    if len(data) < 4 or len(data) > MAX_APK or data[:4] not in (
        b"PK\x03\x04", b"PK\x05\x06", b"PK\x07\x08"
    ):
        raise BridgeError("NOT_APK", "Expected a bounded ZIP-format Android APK")
    digest = hashlib.sha256(data).hexdigest()
    if not HEX.fullmatch(claimed_sha) or not hmac.compare_digest(digest, claimed_sha):
        raise BridgeError("HASH_MISMATCH", "Selected APK hash is different from upload")
    validate_apk_archive(data)
    with tempfile.TemporaryDirectory(prefix="jarvis-rea-class-search-") as root:
        apk = Path(root, "selected.apk")
        apk.write_bytes(data)
        os.chmod(apk, 0o600)
        out = Path(root, "stdout.json")
        command = rea_launcher(rea_command, rea_entry) + [
            "search-android-classes", str(apk), query, "--json"
        ]
        try:
            with out.open("wb") as stdout:
                result = subprocess.run(command, stdout=stdout,
                    stderr=subprocess.DEVNULL, cwd=root, timeout=150, check=False)
        except subprocess.TimeoutExpired:
            raise BridgeError("REA_TIMEOUT", "Class search exceeded deadline", 504)
        except OSError:
            raise BridgeError("REA_UNAVAILABLE", "REA/JADX is unavailable", 503)
        if result.returncode != 0:
            raise BridgeError("REA_FAILED", "REA/JADX class search failed", 502)
        if not 1 <= out.stat().st_size <= MAX_REPORT:
            raise BridgeError("REA_REPORT", "Class search returned an invalid report size", 502)
        raw = out.read_bytes()
        try:
            report = json.loads(raw)
        except (ValueError, UnicodeDecodeError):
            raise BridgeError("REA_REPORT", "REA returned malformed class evidence", 502)
        if (not isinstance(report, dict) or report.get("isError") or
                report.get("error") or report.get("ok") is False or
                report.get("success") is False):
            raise BridgeError("REA_REPORT", "REA reported a failed class search", 502)
        result = report.get("normalized_result")
        if not isinstance(result, dict) or result.get("query") != query:
            raise BridgeError("REA_REPORT", "REA class result is not bound to requested query", 502)
        matches = result.get("matches")
        total = result.get("total_classes")
        if (not isinstance(matches, list) or len(matches) > 10000 or
                type(total) is not int or total < 0 or total > 10000000 or
                len(matches) > total or result.get("coverage") != "complete" or
                any(not isinstance(v, str) or not 1 <= len(v) <= 240 or
                    any(ord(ch) < 32 or ord(ch) == 127 for ch in v) for v in matches)):
            raise BridgeError("REA_REPORT", "REA returned an invalid class inventory", 502)
        return {
            "schema": CLASS_SCHEMA, "state": "ANALYZED",
            "apk_sha256": digest,
            "evidence_sha256": hashlib.sha256(raw).hexdigest(),
            "query": query, "total_classes": total,
            "match_count": len(matches),
            "matches": matches[:MAX_CLASS_MATCHES],
            "truncated": len(matches) > MAX_CLASS_MATCHES,
            "scope": "static-class-name-search-only",
            "limitations": "Static JADX class names; runtime behavior is not established."
        }


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "JarvisREA/1"
    def _respond(self, status, result):
        blob = json.dumps(result, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(blob)))
        self.end_headers()
        self.wfile.write(blob)

    def do_GET(self):
        if self.path != "/v1/health":
            return self._respond(404, {"error": "NOT_FOUND"})
        if not verify_token(self.headers.get("Authorization", ""),
                            os.environ.get("JARVIS_REA_TOKEN", "")):
            return self._respond(401, {"error": "UNAUTHORIZED"})
        if not LOCK.acquire(blocking=False):
            return self._respond(429, {"error": "REA_BUSY"})
        try:
            provider = health_status(os.environ.get("JARVIS_REA_COMMAND", "rea"),
                                     os.environ.get("JARVIS_REA_ENTRY") or None)
            self._respond(200, {
                "schema": "jarvis.rea.health.v1",
                "state": "BRIDGE_REACHABLE",
                "engine": provider,
                "limitations": "REA doctor only; no APK analysis or runtime execution was performed."
            })
        except Exception:
            self._respond(500, {"error": "INTERNAL"})
        finally:
            LOCK.release()

    def do_POST(self):
        if self.path not in ("/v1/apk/analyze", "/v1/apk/classes/search"):
            return self._respond(404, {"error": "NOT_FOUND"})
        token = os.environ.get("JARVIS_REA_TOKEN", "")
        if not verify_token(self.headers.get("Authorization", ""), token):
            return self._respond(401, {"error": "UNAUTHORIZED"})
        if self.headers.get("Content-Type", "").split(";")[0].strip() != "application/vnd.android.package-archive":
            return self._respond(415, {"error": "CONTENT_TYPE"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if not 4 <= length <= MAX_APK:
            return self._respond(413, {"error": "APK_SIZE"})
        if not LOCK.acquire(blocking=False):
            return self._respond(429, {"error": "REA_BUSY"})
        try:
            payload = self.rfile.read(length)
            if len(payload) != length:
                raise BridgeError("TRUNCATED_UPLOAD", "Incomplete APK upload")
            if self.path == "/v1/apk/classes/search":
                result = search_apk_classes(payload,
                    self.headers.get("X-APK-SHA256", "").strip(),
                    self.headers.get("X-REA-CLASS-QUERY", "").strip(),
                    os.environ.get("JARVIS_REA_COMMAND", "rea"),
                    os.environ.get("JARVIS_REA_ENTRY") or None)
            else:
                result = analyze_apk(payload, self.headers.get("X-APK-SHA256", "").strip(),
                                     os.environ.get("JARVIS_REA_COMMAND", "rea"),
                                     os.environ.get("JARVIS_REA_ENTRY") or None)
            self._respond(200, result)
        except BridgeError as error:
            self._respond(error.status, {"error": error.code, "message": str(error)})
        except Exception:
            # No stack traces or secret-bearing CLI stdout are exposed remotely.
            self._respond(500, {"error": "INTERNAL"})
        finally:
            LOCK.release()

    def log_message(self, format, *args):
        # Never log Authorization, APK content or analysis evidence.
        pass


def main():
    cert = os.environ.get("JARVIS_REA_TLS_CERT", "")
    key = os.environ.get("JARVIS_REA_TLS_KEY", "")
    token = os.environ.get("JARVIS_REA_TOKEN", "")
    if not cert or not key or len(token) < 24:
        raise SystemExit("Configure TLS certificate, TLS key and a 24+ character JARVIS_REA_TOKEN")
    host = os.environ.get("JARVIS_REA_HOST", "127.0.0.1")
    port = int(os.environ.get("JARVIS_REA_PORT", "8765"))
    server = http.server.ThreadingHTTPServer((host, port), Handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(cert, key)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    print(f"REA bridge listening on HTTPS {host}:{port} (authenticated; no APK execution)", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
