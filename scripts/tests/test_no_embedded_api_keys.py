from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_no_embedded_api_keys import BUILD_SOURCES, verify, verify_sources


class EmbeddedCredentialTests(unittest.TestCase):
    def apk(self, entries):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        apk = Path(temp.name) / "test.apk"
        with zipfile.ZipFile(apk, "w") as package:
            for filename, data in entries.items():
                package.writestr(filename, data)
        return apk

    def test_package_with_user_key_settings_and_public_urls_is_accepted(self):
        verify(self.apk({"classes.dex": b"ApiKeyStore\x00AndroidKeyStore\x00jarvis_api_keys",
                         "assets/config.json": b'{"url":"https://example.com/chat"}'}))

    def test_gemini_key_in_dex_is_rejected_without_logging_it(self):
        synthetic = "AIza" + "a" * 35
        with self.assertRaisesRegex(ValueError, "Provider credential pattern in classes.dex") as error:
            verify(self.apk({"classes.dex": b"\x00" + synthetic.encode() + b"\x00"}))
        self.assertNotIn(synthetic, str(error.exception))

    def test_provider_key_in_text_asset_is_rejected(self):
        verify_path = self.apk({"assets/config.json": b'{"key":"sk-proj-' + b"b" * 40 + b'"}'})
        with self.assertRaisesRegex(ValueError, "Provider credential pattern in assets/config.json"):
            verify(verify_path)

    def test_retired_embedded_field_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Retired embedded provider credential field"):
            verify(self.apk({"classes2.dex": b"BETA_GEMINI_API_KEY"}))

    def test_resource_table_credentials_are_checked(self):
        with self.assertRaisesRegex(ValueError, "Provider credential pattern in resources.arsc"):
            verify(self.apk({"resources.arsc": b"\x00AIza" + b"c" * 35 + b"\x00"}))

    def test_random_voice_archive_and_native_payloads_are_excluded(self):
        synthetic = b"AIza" + b"d" * 35
        verify(self.apk({"assets/voice-packs/kokoro.tar.bz2": synthetic,
                         "lib/arm64-v8a/libsherpa-onnx.so": synthetic}))

    def test_removed_build_time_credential_configuration_stays_removed(self):
        verify_sources(Path(__file__).resolve().parents[2])

    def test_source_guard_rejects_secret_injection_without_logging_value(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        for filename in BUILD_SOURCES:
            path = root / filename
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("// no provider credentials\n")
        (root / BUILD_SOURCES[0]).write_text('System.getenv("JARVIS_BETA_GEMINI_API_KEY")')
        with self.assertRaisesRegex(ValueError, "Build-time provider credential configuration"):
            verify_sources(root)


if __name__ == "__main__":
    unittest.main()
