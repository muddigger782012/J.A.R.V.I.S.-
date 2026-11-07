"""Executable, dependency-free contract tests for the REA sidecar."""
import hashlib
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


class ReaBridgeTests(unittest.TestCase):
    APK = b"PK\x03\x04" + b"test-fixture-not-an-executable-app"
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

    def test_artifact_hash_is_bound_to_real_cli_json_without_executing_apk(self):
        with tempfile.TemporaryDirectory() as root:
            report = bridge.analyze_apk(self.APK, self.DIGEST,
                                        sys.executable, self.fake_rea(root))
        self.assertEqual(report["schema"], bridge.SCHEMA)
        self.assertEqual(report["state"], "ANALYZED")
        self.assertEqual(report["apk_sha256"], self.DIGEST)
        self.assertIn("normalized_result", report["excerpt"])
        self.assertFalse(report["truncated"])
        self.assertEqual(len(report["evidence_sha256"]), 64)
        self.assertIn("not proven", report["limitations"])

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
