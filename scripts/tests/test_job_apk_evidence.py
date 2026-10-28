from pathlib import Path
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from job_apk_evidence import collect


class JobApkEvidenceTests(unittest.TestCase):
    """SDK output is an explicit fixture; Android tests verify actual APK signatures."""
    def sdk(self, args, text):
        if args[1] == 'dump':
            return "package: name='com.jarvis.developer' versionCode='17' versionName='0.1.17'\nnative-code: 'x86_64'\n"
        return 'Signer #1 certificate SHA-256 digest: ' + 'c'*64 + '\n'

    def test_receipt_binds_actual_file_bytes_and_build_identity_without_installing(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); data = b'explicit fake APK for metadata contract only'
            (root/'developer-debug.apk').write_bytes(data)
            result = collect(root, 'DEVELOPER', 'debug', 'a'*40, 42, 3, root, self.sdk)
            self.assertEqual('a'*40, result['sourceCommit'])
            self.assertEqual(3, result['runAttempt'])
            self.assertEqual(hashlib.sha256(data).hexdigest(), result['apks'][0]['sha256'])
            self.assertEqual(['x86_64'], result['apks'][0]['abis'])
            self.assertNotIn('installed', result)

    def test_package_mismatch_or_unsigned_output_cannot_produce_provenance(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); (root/'app.apk').write_bytes(b'fixture')
            for mutate in ('package', 'signer'):
                def bad(args, text):
                    output = self.sdk(args, text)
                    return output.replace('com.jarvis.developer', 'com.assistant.core.beta') if mutate == 'package' else output.replace('Signer #1', 'Unverified')
                with self.subTest(mutate=mutate), self.assertRaises(AssertionError):
                    collect(root, 'DEVELOPER', 'production', 'a'*40, 42, 1, root, bad)

    def test_symlinked_or_empty_payload_and_bad_workflow_identity_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); file = root/'original'; file.write_bytes(b'fixture')
            (root/'app.apk').symlink_to(file)
            with self.assertRaises(AssertionError):
                collect(root, 'DEVELOPER', 'debug', 'a'*40, 42, 1, root, self.sdk)
            (root/'app.apk').unlink(); (root/'app.apk').touch()
            with self.assertRaises(AssertionError):
                collect(root, 'DEVELOPER', 'debug', 'a'*40, 42, 1, root, self.sdk)
            with self.assertRaises(AssertionError):
                collect(root, 'DEVELOPER', 'debug', 'main', 0, 0, root, self.sdk)

    def test_cli_cannot_attest_a_different_checkout(self):
        with tempfile.TemporaryDirectory() as folder:
            env = dict(os.environ, GITHUB_SHA='0'*40, GITHUB_RUN_ID='42', GITHUB_RUN_ATTEMPT='1', ANDROID_HOME=folder)
            env.pop('JARVIS_ARTIFACT_SOURCE', None)
            result = subprocess.run([sys.executable, str(Path(__file__).resolve().parents[1]/'job_apk_evidence.py'),
                                     '--directory', folder, '--target', 'DEVELOPER', '--signing', 'production'],
                                    capture_output=True, text=True, env=env)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse((Path(folder)/'development-artifact.json').exists())
            self.assertNotIn('Traceback', result.stderr)
