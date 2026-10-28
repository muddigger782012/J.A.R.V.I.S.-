import hashlib
import json
from pathlib import Path
import sys
import tempfile
import subprocess
import os
import unittest
from unittest.mock import patch
import zipfile
import re

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import personal_release as release


class PersonalReleaseTests(unittest.TestCase):
    def test_job_provenance_does_not_change_the_existing_publisher_artifact_contract(self):
        workflow = (Path(__file__).resolve().parents[2]/'.github/workflows/build-jarvis.yml').read_text()
        original = re.search(r'(?ms)^      - name: Upload APK artifact\n(.*?)(?=^      - name:|\Z)', workflow)
        self.assertIsNotNone(original)
        path = re.search(r'^          path: (.+)$', original[1], re.M)
        self.assertIsNotNone(path)
        self.assertEqual('app/build/outputs/apk/release/*.apk', path[1])
        # The real publisher deliberately rejects additional files in its APK input.
        self.assertEqual(3, len(self.verified()['apks']))
        (self.apks/'development-artifact.json').write_text('{}')
        with self.assertRaisesRegex(ValueError, 'exactly'):
            self.verified()
    def test_shared_integration_changes_invalidate_real_git_source_equivalence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            env = dict(os.environ, GIT_AUTHOR_NAME='Release Test', GIT_AUTHOR_EMAIL='test@example.invalid',
                       GIT_COMMITTER_NAME='Release Test', GIT_COMMITTER_EMAIL='test@example.invalid')
            def git(*args):
                return subprocess.check_output(['git', '-C', str(root), *args], env=env, text=True).strip()
            git('init', '-q')
            source = root / 'integration/src/main/kotlin/Bridge.kt'
            source.parent.mkdir(parents=True)
            source.write_text('const val VERSION = 1\n')
            git('add', '.'); git('commit', '-qm', 'Initial shared source')
            first = git('rev-parse', 'HEAD')
            (root / 'artifacts').mkdir()
            (root / 'artifacts/latest.txt').write_text('Metadata only\n')
            git('add', '.'); git('commit', '-qm', 'Publication metadata')
            metadata = git('rev-parse', 'HEAD')
            source.write_text('const val VERSION = 2\n')
            git('add', '.'); git('commit', '-qm', 'Changed bridge implementation')
            changed = git('rev-parse', 'HEAD')
            previous = Path.cwd()
            try:
                os.chdir(root)
                self.assertTrue(release.same_inputs(first, metadata, release.APP_INPUTS))
                self.assertFalse(release.same_inputs(first, changed, release.APP_INPUTS))
            finally:
                os.chdir(previous)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apks = self.root / 'apks'
        self.apks.mkdir()
        self.baseline = self.root / 'baseline.apk'
        self.baseline.write_bytes(b'production')
        for abi in release.ABIS:
            self.apk(self.apks / f'app-{abi}-release.apk', abi)

    def apk(self, path, abi, omit=None, extra=None):
        files = {f'lib/{abi}/libsherpa-onnx-jni.so': b'local runtime',
                 release.MODEL + 'tokens.txt': b'tokens',
                 release.MODEL + 'jarvis-keywords.txt': b'keywords',
                 release.MODEL + 'encoder-test.onnx': b'encoder',
                 release.MODEL + 'decoder-test.onnx': b'decoder',
                 release.MODEL + 'joiner-test.onnx': b'joiner',
                 'classes.dex': b'production'}
        if omit:
            files.pop(omit)
        files.update(extra or {})
        with zipfile.ZipFile(path, 'w') as archive:
            for name, value in files.items():
                archive.writestr(name, value)

    def identity(self, path):
        abi = next((a for a in release.ABIS if a in Path(path).name), None)
        code = '497' if abi else '496'
        return ({'name': 'com.assistant.core', 'versionCode': code,
                 'versionName': '3.6.' + code}, f"native-code: '{abi}'")

    def verified(self, **kwargs):
        with patch.object(release, 'identity', side_effect=self.identity), \
                patch.object(release, 'signers', return_value=['a' * 64]), \
                patch.object(release, 'sdk_tool', return_value='aapt'), \
                patch.object(release, 'command', return_value='production manifest'):
            return release.verify_apks(self.apks, self.baseline, '3.6.497', 100, 'b' * 40, **kwargs)

    def test_all_signed_architectures_generate_manifest_and_checksums(self):
        manifest = self.verified()
        release.validate_manifest(manifest, '3.6.497')
        output = self.root / 'metadata'
        release.write_manifest(manifest, output)
        self.assertEqual(manifest, json.loads((output / 'personal-3.6.497.json').read_text()))
        self.assertEqual(3, len((output / 'SHA256SUMS.txt').read_text().splitlines()))

    def test_bad_versions_cannot_become_paths_or_shell_arguments(self):
        for value in ('../3.6.497', '3.6.497\n', '3.6.0', '3.6.-1', '3.6.2100000000',
                      '3.6.497;reboot', '3.5.497-beta', '4.0.497', '3.6.0497'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                release.version_code(value)

    def test_missing_extra_or_beta_apks_are_rejected(self):
        (self.apks / 'beta.apk').write_bytes(b'not personal')
        with self.assertRaisesRegex(ValueError, 'exactly'):
            self.verified()
        (self.apks / 'beta.apk').unlink()
        (self.apks / 'app-x86_64-release.apk').unlink()
        with self.assertRaisesRegex(ValueError, 'exactly'):
            self.verified()

    def test_production_signing_identity_cannot_change(self):
        with patch.object(release, 'identity', side_effect=self.identity), \
                patch.object(release, 'signers', side_effect=[['a' * 64], ['b' * 64]]):
            with self.assertRaisesRegex(ValueError, 'signer differs'):
                release.verify_apks(self.apks, self.baseline, '3.6.497', 100, 'b' * 40)

    def test_wrong_version_and_abi_are_rejected(self):
        for package, native in [({'versionCode': '498', 'versionName': '3.6.497'}, "native-code: 'arm64-v8a'"),
                                ({'versionCode': '497', 'versionName': '3.6.497'}, "native-code: 'arm64-v8a' 'x86_64'")]:
            with self.subTest(native=native, package=package), \
                    patch.object(release, 'identity', side_effect=[self.identity(self.baseline), (package, native)]), \
                    patch.object(release, 'signers', return_value=['a' * 64]), self.assertRaises(ValueError):
                release.verify_apks(self.apks, self.baseline, '3.6.497', 100, 'b' * 40)

    def test_missing_wake_models_and_beta_payload_are_rejected(self):
        path = self.apks / 'app-arm64-v8a-release.apk'
        self.apk(path, 'arm64-v8a', omit=release.MODEL + 'encoder-test.onnx')
        with self.assertRaisesRegex(ValueError, 'Wake-word model'):
            self.verified()
        self.apk(path, 'arm64-v8a', extra={'assets/voice-packs/kokoro.tar.bz2': b'beta'})
        with self.assertRaisesRegex(ValueError, 'Beta'):
            self.verified()

    def test_acceptance_fixture_cannot_leak_into_production(self):
        path = self.apks / 'app-arm64-v8a-release.apk'
        self.apk(path, 'arm64-v8a', extra={'classes.dex': b'private-fixture-password'})
        with self.assertRaisesRegex(ValueError, 'fixture'):
            self.verified()
        self.apk(path, 'arm64-v8a')
        with patch.object(release, 'identity', side_effect=self.identity), \
                patch.object(release, 'signers', return_value=['a' * 64]), \
                patch.object(release, 'sdk_tool', return_value='aapt'), \
                patch.object(release, 'command', return_value='ScreenControlProbeService'), \
                self.assertRaisesRegex(ValueError, 'Test-only'):
            release.verify_apks(self.apks, self.baseline, '3.6.497', 100, 'b' * 40)

    def test_historical_release_never_downgrades_updater(self):
        manifest = self.verified()
        with self.assertRaisesRegex(ValueError, 'newer updater'):
            release.updater_decision(manifest, 498, 'x')
        self.assertEqual('keep-newer', release.updater_decision(manifest, 498, 'x', reviewed_older=True))
        self.assertEqual('update', release.updater_decision(manifest, 496, 'x'))
        arm = next(r for r in manifest['apks'] if r['abi'] == 'arm64-v8a')
        self.assertEqual('unchanged', release.updater_decision(manifest, 497, arm['sha256']))
        with self.assertRaisesRegex(ValueError, 'different APK bytes'):
            release.updater_decision(manifest, 497, '0' * 64, reviewed_older=True)

    def test_manifest_path_traversal_duplicates_or_incomplete_verification_rejected(self):
        manifest = self.verified()
        for mutate in (lambda m: m['apks'][0].update(artifact='../app.apk'),
                       lambda m: m['apks'][0].update(abi='x86_64'),
                       lambda m: m['apks'][0].update(productionSignerMatchesBaseline=False),
                       lambda m: m['apks'][0].update(sha256='not a digest')):
            copy = json.loads(json.dumps(manifest))
            mutate(copy)
            with self.assertRaises(ValueError):
                release.validate_manifest(copy, '3.6.497')

    def test_downloads_exact_hashes_checksums_and_deterministic_zip(self):
        manifest = self.verified()
        first, second = self.root / 'first', self.root / 'second'
        release.prepare_downloads(manifest, self.apks, first)
        release.prepare_downloads(manifest, self.apks, second)
        self.assertEqual({p.name: release.digest(p) for p in first.iterdir()},
                         {p.name: release.digest(p) for p in second.iterdir()})
        lines = (first / 'SHA256SUMS.txt').read_text().splitlines()
        self.assertEqual(4, len(lines))
        for line in lines:
            checksum, name = line.split('  ')
            self.assertEqual(checksum, release.digest(first / name))
        with zipfile.ZipFile(first / 'Jarvis-3.6.497.zip') as archive:
            self.assertEqual(['Jarvis-3.6.497.apk'], archive.namelist())
            self.assertEqual((self.apks / 'app-arm64-v8a-release.apk').read_bytes(), archive.read(archive.namelist()[0]))

    def test_tampered_apk_cannot_be_packaged(self):
        manifest = self.verified()
        (self.apks / 'app-arm64-v8a-release.apk').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'differs'):
            release.prepare_downloads(manifest, self.apks, self.root / 'assets')

    def test_acceptance_gate_requires_every_real_service_job(self):
        run = {'id': 11, 'head_sha': 'b' * 40, 'head_repository': {'full_name': 'owner/repo'},
               'status': 'completed', 'conclusion': 'success'}
        def api(repo, path):
            if '/runs?' in path:
                return {'workflow_runs': [run]}
            return {'jobs': [{'name': n, 'conclusion': 'success'} for n in ('test', 'gateway', 'theme')]}
        with patch.object(release, 'api', side_effect=api), \
                patch.object(release, 'same_inputs', return_value=True), \
                patch.object(release.subprocess, 'run') as process:
            process.return_value.returncode = 0
            with self.assertRaisesRegex(ValueError, 'missing/skipped'):
                release.gate_checks('owner/repo', 'b' * 40, 0)

    def test_acceptance_gate_rejects_failed_or_foreign_runs(self):
        for foreign in (False, True):
            run = {'id': 11, 'head_sha': 'b' * 40,
                   'head_repository': {'full_name': 'outsider/repo' if foreign else 'owner/repo'},
                   'status': 'completed', 'conclusion': 'failure'}
            with self.subTest(foreign=foreign), \
                    patch.object(release, 'api', return_value={'workflow_runs': [run]}), \
                    patch.object(release, 'same_inputs', return_value=True), \
                    patch.object(release.subprocess, 'run') as process:
                process.return_value.returncode = 0
                with self.assertRaises(ValueError):
                    release.gate_checks('owner/repo', 'b' * 40, 0)

    def test_successful_source_equivalent_checks_produce_gate_evidence(self):
        run = {'id': 11, 'head_sha': 'b' * 40, 'head_repository': {'full_name': 'owner/repo'},
               'status': 'completed', 'conclusion': 'success'}
        def api(repo, path):
            if '/runs?' in path:
                return {'workflow_runs': [run]}
            return {'jobs': [{'name': n, 'conclusion': 'success'} for n in ('test', 'gateway', 'voice-playback', 'ui-layout',
                'android-compatibility (26)', 'android-compatibility (34)', 'theme')]}
        with patch.object(release, 'api', side_effect=api), \
                patch.object(release, 'same_inputs', return_value=True), \
                patch.object(release.subprocess, 'run') as process:
            process.return_value.returncode = 0
            self.assertEqual(2, len(release.gate_checks('owner/repo', 'b' * 40, 0)))

    def test_ui_and_each_android_compatibility_gate_cannot_be_missing_failed_or_skipped(self):
        run = {'id': 11, 'head_sha': 'b' * 40, 'head_repository': {'full_name': 'owner/repo'},
               'status': 'completed', 'conclusion': 'success'}
        names = ('test', 'gateway', 'voice-playback', 'ui-layout', 'android-compatibility (26)', 'android-compatibility (34)', 'theme')
        for target in ('ui-layout', 'android-compatibility (26)', 'android-compatibility (34)'):
            for result in ('missing', 'failure', 'skipped'):
                def api(repo, path):
                    if '/runs?' in path: return {'workflow_runs': [run]}
                    return {'jobs': [{'name': n, 'conclusion': result if n == target else 'success'}
                                     for n in names if n != target or result != 'missing']}
                with self.subTest(target=target, result=result), patch.object(release, 'api', side_effect=api), \
                        patch.object(release, 'same_inputs', return_value=True), patch.object(release.subprocess, 'run') as process:
                    process.return_value.returncode = 0
                    with self.assertRaisesRegex(ValueError, 'missing/skipped'):
                        release.gate_checks('owner/repo', 'b' * 40, 0)

    def test_unpublished_draft_is_verified_by_release_record_without_a_tag_lookup(self):
        draft = {'tag_name': 'jarvis-3.5.496', 'prerelease': False, 'draft': True, 'assets': []}
        with patch.object(release, 'command', return_value=json.dumps(
                {'apiUrl': 'https://api.github.com/repos/owner/repo/releases/42'})), \
                patch.object(release, 'api', return_value=draft) as api:
            self.assertEqual(draft, release.release_metadata('owner/repo', 'jarvis-3.5.496'))
            api.assert_called_once_with('owner/repo', 'releases/42')

    def test_draft_lookup_rejects_another_repo_tag_or_prerelease(self):
        for url, result in [
            ('https://api.github.com/repos/other/repo/releases/42', {}),
            ('https://api.github.com/repos/owner/repo/releases/tags/jarvis-3.5.496', {}),
            ('https://api.github.com/repos/owner/repo/releases/42', {'tag_name': 'other', 'prerelease': False}),
            ('https://api.github.com/repos/owner/repo/releases/42', {'tag_name': 'jarvis-3.5.496', 'prerelease': True})
        ]:
            with self.subTest(url=url, result=result), \
                    patch.object(release, 'command', return_value=json.dumps({'apiUrl': url})), \
                    patch.object(release, 'api', return_value=result), self.assertRaises(ValueError):
                release.release_metadata('owner/repo', 'jarvis-3.5.496')


if __name__ == '__main__':
    unittest.main()
