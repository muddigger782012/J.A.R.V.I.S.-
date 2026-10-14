import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_bundled_voice_apk import ASSET, verify


class BundledVoiceApkTests(unittest.TestCase):
    def apk(self, data=b"verified archive", stored=True, digest=None, size=None, include=True):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        apk = Path(temp.name) / "beta.apk"
        metadata = {"bytes": len(data) if size is None else size,
                    "sha256": hashlib.sha256(data).hexdigest() if digest is None else digest}
        with zipfile.ZipFile(apk, "w") as package:
            package.writestr("assets/neural-voice-pack.json", json.dumps(metadata))
            if include:
                package.writestr(ASSET, data, compress_type=zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED)
        return apk

    def test_verified_payload_is_accepted(self):
        verify(self.apk())

    def test_corrupt_payload_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "does not match"):
            verify(self.apk(digest="0" * 64))

    def test_truncated_payload_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "size/compression"):
            verify(self.apk(size=100))

    def test_double_compression_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "size/compression"):
            verify(self.apk(stored=False))

    def test_normal_app_must_not_contain_beta_payload(self):
        verify(self.apk(include=False), absent=True)
        with self.assertRaisesRegex(ValueError, "unexpectedly contains"):
            verify(self.apk(), absent=True)

    def test_beta_cannot_pass_without_payload(self):
        with self.assertRaises(KeyError):
            verify(self.apk(include=False))
