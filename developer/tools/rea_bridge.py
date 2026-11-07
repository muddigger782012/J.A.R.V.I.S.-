#!/usr/bin/env python3
"""Isolated HTTPS APK-to-REA CLI bridge for J.A.R.V.I.S. Developer.

Operator-owned host only: requires Python 3, a verified rea-agents installation,
JDK and REA's pinned JADX engine. No Android app code executes in this process.
The APK is never executed; only static REA inspection is requested.
"""
import hashlib
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

MAX_APK = 24 * 1024 * 1024
MAX_REPORT = 1024 * 1024
MAX_EXCERPT = 30000
HEX = re.compile(r"^[0-9a-f]{64}$")
SCHEMA = "jarvis.rea.apk.v1"
LOCK = threading.BoundedSemaphore(1)


class BridgeError(Exception):
    def __init__(self, code, message, status=400):
        super().__init__(message)
        self.code, self.status = code, status


def verify_token(received, expected):
    if not expected or len(expected) < 24:
        raise BridgeError("CONFIGURATION", "Bridge token must contain at least 24 characters", 503)
    return hmac.compare_digest(received, "Bearer " + expected)


def analyze_apk(data, claimed_sha, rea_command="rea"):
    if len(data) < 4 or len(data) > MAX_APK or data[:4] not in (
        b"PK\x03\x04", b"PK\x05\x06", b"PK\x07\x08"
    ):
        raise BridgeError("NOT_APK", "Expected a bounded ZIP-format Android APK")
    sha = hashlib.sha256(data).hexdigest()
    if not HEX.fullmatch(claimed_sha) or not hmac.compare_digest(sha, claimed_sha):
        raise BridgeError("HASH_MISMATCH", "APK SHA-256 did not match the submitted bytes")
    with tempfile.TemporaryDirectory(prefix="jarvis-rea-") as root:
        apk = Path(root, "selected.apk")
        apk.write_bytes(data)
        os.chmod(apk, 0o600)
        full = Path(root, "full-evidence.json")
        out = Path(root, "stdout.json")
        err = Path(root, "stderr.log")
        # Argument vector only; no shell or source-code execution. In a
        # private per-job directory, nothing is shared with other analyses.
        cmd = [rea_command, "inspect-android-package", str(apk),
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
        if not isinstance(parsed, dict) or not parsed or parsed.get("ok") is False or (
            parsed.get("success") is False
        ) or parsed.get("error") or parsed.get("isError"):
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
            "truncated": truncated,
            "scope": "static-reverse-engineering-only",
            "engine": "REA inspect-android-package (JADX)",
            "limitations": "Static evidence; runtime behavior, authenticity and completeness not proven."
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

    def do_POST(self):
        if self.path != "/v1/apk/analyze":
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
            result = analyze_apk(payload, self.headers.get("X-APK-SHA256", "").strip(),
                                 os.environ.get("JARVIS_REA_COMMAND", "rea"))
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
