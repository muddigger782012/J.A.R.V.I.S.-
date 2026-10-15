import io
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from encrypt_candidate import encrypt
from stage_update_channels import ABIS, PERMANENT_SIGNER, file_digest


class CandidateEncryptionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.keys = tempfile.TemporaryDirectory()
        cls.key, cls.cert = [Path(cls.keys.name) / name for name in ('key.pem', 'cert.pem')]
        subprocess.run(['openssl', 'req', '-new', '-x509', '-newkey', 'rsa:2048', '-nodes',
                        '-days', '1', '-subj', '/CN=Disposable candidate test/',
                        '-keyout', str(cls.key), '-out', str(cls.cert)],
                       check=True, capture_output=True)

    @classmethod
    def tearDownClass(cls):
        cls.keys.cleanup()

    def stage(self, root):
        stage = root / 'stage'
        for channel, prefix, package in [('jarvis', 'Jarvis', 'com.assistant.core'),
                                          ('beta', 'Beta', 'com.assistant.core.beta')]:
            folder = stage / 'releases' / channel
            folder.mkdir(parents=True)
            artifacts = []
            for abi in ABIS:
                path = folder / f'{prefix}-{abi}.apk'
                path.write_bytes(f'fixture-only-{channel}-{abi}'.encode())
                artifacts.append({'abi': abi, 'sizeBytes': path.stat().st_size,
                                  'sha256': file_digest(path)})
            (folder / f'{prefix}.apk').write_bytes(f'fixture-universal-{channel}'.encode())
            metadata = stage / 'pointers/updates' / f'{channel}.json'
            metadata.parent.mkdir(parents=True, exist_ok=True)
            metadata.write_text(json.dumps({'channel': channel, 'applicationId': package,
                                           'sourceCommit': 'b' * 40, 'versionCode': 395,
                                           'artifacts': artifacts}))
        bridge = stage / 'pointers/artifacts/beta.apk'
        bridge.parent.mkdir(parents=True)
        bridge.write_bytes(b'fixture-universal-bridge')
        receipt = {'sourceCommit': 'b' * 40, 'versionCode': 395,
                   'channels': ['jarvis', 'beta'],
                   'files': {str(path.relative_to(stage)): file_digest(path)
                             for path in stage.rglob('*') if path.is_file()}}
        (stage / 'receipt.json').write_text(json.dumps(receipt))
        return stage

    def test_encrypted_round_trip_includes_bridge_without_duplicate_voice_archives(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stage = self.stage(root)
            output = root / 'candidate.cms'
            encrypt(stage, 'b' * 40, self.cert, output)
            self.assertNotIn(b'fixture-universal-beta', output.read_bytes())
            decrypted = subprocess.run(['openssl', 'cms', '-decrypt', '-binary', '-inform',
                                        'DER', '-in', str(output), '-inkey', str(self.key)],
                                       check=True, capture_output=True).stdout
            with tarfile.open(fileobj=io.BytesIO(decrypted)) as archive:
                self.assertEqual(set(archive.getnames()), {'Jarvis.apk', 'Beta.apk',
                    'Beta-bridge.apk', 'updates/jarvis.json', 'updates/beta.json',
                    'source-stage-receipt.json', 'candidate.json'})
                manifest = json.load(archive.extractfile('candidate.json'))
                self.assertEqual(manifest['signingCertificateSha256'], PERMANENT_SIGNER)
                self.assertEqual(manifest['bridgeVersionCode'], 394)
                self.assertTrue(manifest['metadataUsesPublicUrls'])
                self.assertEqual(archive.extractfile('Beta-bridge.apk').read(),
                                 b'fixture-universal-bridge')

    def test_tampered_staging_never_produces_an_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stage = self.stage(root)
            (stage / 'releases/beta/Beta.apk').write_bytes(b'changed')
            output = root / 'candidate.cms'
            with self.assertRaises(ValueError):
                encrypt(stage, 'b' * 40, self.cert, output)
            self.assertFalse(output.exists())

    def test_rejects_output_inside_staging_and_incorrect_recipient(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stage = self.stage(root)
            with self.assertRaises(ValueError):
                encrypt(stage, 'b' * 40, self.cert, stage / 'candidate.cms')
            output = root / 'candidate.cms'
            with self.assertRaises(subprocess.CalledProcessError):
                encrypt(stage, 'b' * 40, root / 'missing.pem', output)
            self.assertFalse(output.exists())
            self.assertFalse(output.with_name(output.name + '.part').exists())


if __name__ == '__main__':
    unittest.main()
