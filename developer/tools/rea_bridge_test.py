"""Executable, dependency-free contract tests for the REA sidecar."""
import hashlib
import io
import zipfile
import http.client
import importlib.util
import json
import os
import sys
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

HERE = Path(__file__).parent
spec = importlib.util.spec_from_file_location("rea_bridge", HERE / "rea_bridge.py")
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)



def make_apk(entries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, contents in entries:
            archive.writestr(name, contents)
    return buffer.getvalue()


class ReaBridgeTests(unittest.TestCase):
    APK = make_apk([("AndroidManifest.xml", b"test-xml-stub"), ("classes.dex", b"test-dex-stub")])
    DIGEST = hashlib.sha256(APK).hexdigest()

    def fake_rea(self, directory, result='{"normalized_result":{"package":"example.test"}}'):
        tool = Path(directory, "rea-mock")
        tool.write_text("#!/usr/bin/env python3\nimport sys\n"
                        "from pathlib import Path\n"
                        "assert sys.argv[1] == 'inspect-android-package'\n"
                        "assert Path(sys.argv[2]).is_file()\n"
                        "assert '--detail' in sys.argv and 'summary' in sys.argv\n"
                        "assert '--evidence_output' in sys.argv and '--json' in sys.argv\n"
                        f"print({result!r})\n", encoding="utf8")
        tool.chmod(0o700)
        return str(tool)

    def test_rea_health_doctor_checks_real_executable_without_analyzing_an_apk(self):
        with tempfile.TemporaryDirectory() as root:
            healthy = Path(root, "doctor-ok.py")
            healthy.write_text("#! /usr/bin/env python3\nimport sys\n"
                               "assert sys.argv[1:] == ['doctor', '--provider', 'jadx', '--json']\n"
                               "sys.exit(0)\n")
            failed = Path(root, "doctor-fail.py")
            failed.write_text("#! /usr/bin/env python3\nimport sys\nsys.exit(2)\n")
            self.assertEqual(bridge.health_status(sys.executable, str(healthy)), "DOCTOR_PASSED")
            self.assertEqual(bridge.health_status(sys.executable, str(failed)), "DOCTOR_FAILED")
            self.assertEqual(bridge.health_status(sys.executable, "/no/such/script.mjs"), "DOCTOR_FAILED")

    def test_authenticated_health_reports_doctor_status_without_apk_upload(self):
        token = "test-rea-bridge-doctor-token-012345"
        server = bridge.http.server.ThreadingHTTPServer(("127.0.0.1", 0), bridge.Handler)
        worker = threading.Thread(target=server.handle_request, daemon=True)
        worker.start()
        try:
            with patch.dict(os.environ, {"JARVIS_REA_TOKEN": token}):
                with patch.object(bridge, "health_status", return_value="DOCTOR_PASSED") as probe:
                    conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=5)
                    conn.request("GET", "/v1/health", headers={"Authorization": "Bearer " + token})
                    response = conn.getresponse()
                    self.assertEqual(response.status, 200)
                    result = json.loads(response.read())
                    self.assertEqual(result["schema"], "jarvis.rea.health.v1")
                    self.assertEqual(result["state"], "BRIDGE_REACHABLE")
                    self.assertEqual(result["engine"], "DOCTOR_PASSED")
                    self.assertIn("no APK analysis", result["limitations"])
                    self.assertNotIn(token, json.dumps(result))
                    self.assertEqual(probe.call_count, 1)
                    conn.close()
        finally:
            server.server_close()
            worker.join(timeout=3)

    def test_artifact_hash_is_bound_to_real_cli_json_without_executing_apk(self):
        with tempfile.TemporaryDirectory() as root:
            report = bridge.analyze_apk(self.APK, self.DIGEST,
                                        sys.executable, self.fake_rea(root))
        self.assertEqual(report["schema"], bridge.SCHEMA)
        self.assertEqual(report["state"], "ANALYZED")
        self.assertEqual(report["apk_sha256"], self.DIGEST)
        self.assertIn("normalized_result", report["excerpt"])
        self.assertFalse(report["truncated"])
        self.assertEqual(report["archive_entries"], 2)
        self.assertGreater(report["archive_uncompressed_bytes"], 0)
        self.assertEqual(len(report["evidence_sha256"]), 64)
        self.assertIn("not proven", report["limitations"])



    def test_summary_projector_handles_rea_summary_view_without_inventing_claims(self):
        report = {"kind": "android-package-summary", "summary": {
            "package_name": "com.example.ownedtool", "version_name": "2.1",
            "min_sdk": "26", "target_sdk": "34",
            "permissions": ["android.permission.CAMERA", "android.permission.INTERNET"],
            "class_count": 437, "resource_count": 83,
            "manifest": {"status": "partial"},
            "components": {"status": "complete", "items": [
                {"kind": "activity", "name": "com.example.ownedtool.Home"},
                {"kind": "service", "name": "com.example.ownedtool.Sync"},
            ]},
        }}
        summary = bridge.summarize_apk_evidence(report)
        self.assertEqual(summary["package_name"], "com.example.ownedtool")
        self.assertEqual(summary["class_count"], 437)
        self.assertEqual(summary["permission_count"], 2)
        self.assertEqual(summary["component_count"], 2)
        self.assertEqual(summary["manifest_status"], "partial")
        self.assertNotIn("Home", json.dumps(summary))
        self.assertIsNone(bridge.summarize_apk_evidence({"unknown": "no facts"}))

    def test_summary_projector_bounds_untrusted_values(self):
        report = {"package_name": "unsafe\npackage", "version_name": "test",
                  "min_sdk": -1, "target_sdk": "not-a-sdk", "class_count": -3,
                  "resource_count": True, "permissions": [
                      "android.permission.INTERNET", "android.permission.INTERNET",
                      "bad\npermission", "com.example.READ"
                  ], "manifest": {"status": "unverified"}}
        summary = bridge.summarize_apk_evidence(report)
        self.assertIsNone(summary["package_name"])
        self.assertIsNone(summary["min_sdk"])
        self.assertIsNone(summary["target_sdk"])
        self.assertIsNone(summary["class_count"])
        self.assertIsNone(summary["resource_count"])
        self.assertEqual(summary["permission_count"], 2)
        self.assertEqual(summary["permissions"],
                         ["android.permission.INTERNET", "com.example.READ"])
        self.assertEqual(summary["manifest_status"], "not_reported")


    def fake_class_rea(self, directory, report=None):
        report = report or ('{"normalized_result":{"query":"MainActivity",'
                            '"total_classes":25,"matches":["a.MainActivity",'
                            '"a.MainActivity$Inner"],"coverage":"complete"}}')
        script = Path(directory, "mock-class-search.py")
        script.write_text("#!/usr/bin/env python3\nimport sys\nfrom pathlib import Path\n"
                          "assert sys.argv[1] == 'search-android-classes'\n"
                          "assert Path(sys.argv[2]).is_file()\n"
                          "assert sys.argv[3:] == ['MainActivity', '--json']\n"
                          f"print({report!r})\n", encoding="utf8")
        return str(script)

    def test_class_search_is_apk_bound_with_fixed_command_and_evidence(self):
        with tempfile.TemporaryDirectory() as root:
            response = bridge.search_apk_classes(self.APK, self.DIGEST,
                "MainActivity", sys.executable, self.fake_class_rea(root))
        self.assertEqual(response["schema"], bridge.CLASS_SCHEMA)
        self.assertEqual(response["apk_sha256"], self.DIGEST)
        self.assertEqual(response["query"], "MainActivity")
        self.assertEqual(response["total_classes"], 25)
        self.assertEqual(response["matches"], ["a.MainActivity", "a.MainActivity$Inner"])
        self.assertEqual(response["match_count"], 2)
        self.assertEqual(len(response["evidence_sha256"]), 64)

    def test_class_search_rejects_unsafe_selector_hash_or_fabricated_result(self):
        for query in ("", "x", "../Main", "a b", "--help", "Main\nx",
                      "a;id", "x" * 81, "/tmp/repo.apk"):
            with self.subTest(query=query):
                with self.assertRaises(bridge.BridgeError) as failure:
                    bridge.search_apk_classes(self.APK, self.DIGEST, query, "/missing")
                self.assertEqual(failure.exception.code, "CLASS_QUERY")
        with self.assertRaises(bridge.BridgeError) as failure:
            bridge.search_apk_classes(self.APK, "0" * 64, "MainActivity", "/missing")
        self.assertEqual(failure.exception.code, "HASH_MISMATCH")
        with tempfile.TemporaryDirectory() as root:
            wrong = '{"normalized_result":{"query":"Other","total_classes":1,"matches":["a.C"],"coverage":"complete"}}'
            with self.assertRaises(bridge.BridgeError) as failure:
                bridge.search_apk_classes(self.APK, self.DIGEST, "MainActivity",
                    sys.executable, self.fake_class_rea(root, wrong))
        self.assertEqual(failure.exception.code, "REA_REPORT")

    def test_class_search_route_is_authorized_without_dynamic_commands(self):
        token = "test-class-search-auth-token-012345"
        for authorized in (False, True):
            server = bridge.http.server.ThreadingHTTPServer(("127.0.0.1", 0), bridge.Handler)
            worker = threading.Thread(target=server.handle_request, daemon=True)
            worker.start()
            try:
                with patch.dict(os.environ, {"JARVIS_REA_TOKEN": token}):
                    with patch.object(bridge, "search_apk_classes",
                        return_value={"schema": bridge.CLASS_SCHEMA}) as search:
                        headers = {"Content-Type": "application/vnd.android.package-archive",
                                   "X-APK-SHA256": self.DIGEST,
                                   "X-REA-CLASS-QUERY": "MainActivity"}
                        if authorized:
                            headers["Authorization"] = "Bearer " + token
                        conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=5)
                        conn.request("POST", "/v1/apk/classes/search", body=self.APK, headers=headers)
                        response = conn.getresponse()
                        self.assertEqual(response.status, 200 if authorized else 401)
                        response.read()
                        conn.close()
                        self.assertEqual(search.call_count, int(authorized))
            finally:
                server.server_close()
                worker.join(timeout=3)

    def test_archive_preflight_refuses_missing_manifest_traversal_duplicates_and_broken_zip(self):
        candidates = [
            make_apk([("classes.dex", b"no manifest")]),
            make_apk([("../AndroidManifest.xml", b"unsafe path")]),
            make_apk([("AndroidManifest.xml", b"ok"), ("../payload", b"unsafe path")]),
            make_apk([("AndroidManifest.xml", b"ok"), ("AndroidManifest.xml", b"duplicate")]),
            b"PK\x03\x04not-an-actual-zip",
        ]
        for payload in candidates:
            with self.subTest(sha256=hashlib.sha256(payload).hexdigest()):
                with self.assertRaises(bridge.BridgeError) as raised:
                    bridge.analyze_apk(payload, hashlib.sha256(payload).hexdigest(), "/missing")
                self.assertEqual(raised.exception.code, "APK_ARCHIVE")

    def test_archive_preflight_enforces_counts_and_declared_size_bounds(self):
        with patch.object(bridge, "MAX_ARCHIVE_ENTRIES", 1):
            with self.assertRaises(bridge.BridgeError) as raised:
                bridge.analyze_apk(self.APK, self.DIGEST, "/missing")
            self.assertEqual(raised.exception.code, "APK_ARCHIVE")
        with patch.object(bridge, "MAX_UNCOMPRESSED", 4):
            with self.assertRaises(bridge.BridgeError) as raised:
                bridge.analyze_apk(self.APK, self.DIGEST, "/missing")
            self.assertEqual(raised.exception.code, "APK_ARCHIVE")

    def test_invalid_apk_size_and_wrong_sha_refused_before_launch(self):
        with self.assertRaisesRegex(bridge.BridgeError, "ZIP-format"):
            bridge.analyze_apk(b"not an apk", self.DIGEST, "/missing")
        with self.assertRaisesRegex(bridge.BridgeError, "did not match"):
            bridge.analyze_apk(self.APK, "0" * 64, "/missing")

    def test_malformed_cli_or_missing_binary_never_claims_analyzed(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaises(bridge.BridgeError) as raised:
                bridge.analyze_apk(self.APK, self.DIGEST,
                                   sys.executable, self.fake_rea(root, "{garbled"))
        self.assertEqual(raised.exception.code, "REA_REPORT")
        with tempfile.TemporaryDirectory() as root:
            entry = self.fake_rea(root)
            with self.assertRaises(bridge.BridgeError) as raised:
                bridge.analyze_apk(self.APK, self.DIGEST,
                                   "/definitely/missing/rea", entry)
            self.assertEqual(raised.exception.code, "REA_UNAVAILABLE")
            with self.assertRaises(bridge.BridgeError) as missing_entry:
                bridge.analyze_apk(self.APK, self.DIGEST, sys.executable,
                                   str(Path(root, "not-installed.mjs")))
            self.assertEqual(missing_entry.exception.code, "REA_CONFIGURATION")

    def test_generic_success_json_is_not_mistaken_for_rea_evidence(self):
        with tempfile.TemporaryDirectory() as root:
            tool = self.fake_rea(root, '{"success": true, "data": {"fake": true}}')
            with self.assertRaises(bridge.BridgeError) as raised:
                bridge.analyze_apk(self.APK, self.DIGEST, sys.executable, tool)
            self.assertEqual(raised.exception.code, "REA_REPORT")

    def test_token_validation_and_unauthorized_post_do_not_launch_engine(self):
        token = "a-secure-local-test-token-123456"
        self.assertTrue(bridge.verify_token("Bearer " + token, token))
        self.assertFalse(bridge.verify_token("Bearer " + token + "x", token))
        self.assertFalse(bridge.verify_token("Bearer anything", ""))
        server = bridge.http.server.ThreadingHTTPServer(("127.0.0.1", 0), bridge.Handler)
        worker = threading.Thread(target=server.handle_request, daemon=True)
        worker.start()
        try:
            with patch.dict(os.environ, {"JARVIS_REA_TOKEN": token}):
                client = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=5)
                client.request("POST", "/v1/apk/analyze", body=self.APK, headers={
                    "Content-Type": "application/vnd.android.package-archive",
                    "X-APK-SHA256": self.DIGEST})
                response = client.getresponse()
                self.assertEqual(response.status, 401)
                self.assertEqual(json.loads(response.read())["error"], "UNAUTHORIZED")
                client.close()
        finally:
            server.server_close()
            worker.join(timeout=3)

    def test_bridge_never_uses_raw_cli_output_as_a_successful_analysis_on_failure(self):
        with tempfile.TemporaryDirectory() as root:
            binary = self.fake_rea(root, '{"ok": false, "error": "failed"}')
            with self.assertRaises(bridge.BridgeError) as raised:
                bridge.analyze_apk(self.APK, self.DIGEST, sys.executable, binary)
        self.assertEqual(raised.exception.code, "REA_REPORT")


if __name__ == "__main__":
    unittest.main()
