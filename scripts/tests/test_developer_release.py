"""SDK/GitHub responses below are explicit fixtures; the live audit is separate."""
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import developer_release as release

A, B, C = 'a' * 40, 'b' * 40, 'c' * 40


def apk(code, extra=''):
    return ('EXPLICIT_FAKE_APK:' + str(code) + ':' + extra).encode()


def sdk(args, text=True):
    if args[1] == 'dump':
        code = int(Path(args[-1]).read_bytes().decode().split(':')[1])
        return f"package: name='com.jarvis.developer' versionCode='{code}' versionName='0.1.{code}'\n"
    return 'Signer #1 certificate SHA-256 digest: ' + release.SIGNER + '\n'


class GitHubFixture:
    def __init__(self, current=True):
        self.releases, self.data, self.tags, self.calls = {}, {}, {}, []
        self.next_id = 100
        self.after_upload = None
        if current:
            self.add(release.TAG, A, 17)

    def add(self, tag, source, code, data=None):
        self.next_id += 1
        value = apk(code) if data is None else data
        self.releases[tag] = dict(id=self.next_id, tag_name=tag, target_commitish=source,
                                  draft=False, prerelease=True, name='Jarvis Developer 0.1.' + str(code), assets=[])
        self.tags[tag] = source
        self.put(tag, release.APK, value)

    def put(self, tag, name, data):
        self.data[tag, name] = data
        items = self.releases[tag]['assets']
        items[:] = [item for item in items if item['name'] != name]
        items.append(dict(id=99, name=name, size=len(data), digest='sha256:' + hashlib.sha256(data).hexdigest()))

    def api(self, path, method='GET', body=None, missing=False):
        self.calls.append((method, path, copy.deepcopy(body)))
        if method == 'GET':
            if path.startswith('git/commits/'):
                source = path.removeprefix('git/commits/')
                value = {'sha': source} if source in {A, B, C} else None
            elif path.startswith('releases/tags/'):
                value = self.releases.get(path.removeprefix('releases/tags/'))
            elif path.startswith('git/ref/tags/'):
                tag = path.removeprefix('git/ref/tags/')
                value = dict(ref='refs/tags/' + tag, object=dict(type='commit', sha=self.tags[tag])) if tag in self.tags else None
            elif path.startswith('releases/'):
                value = next((item for item in self.releases.values() if item['id'] == int(path.split('/')[1])), None)
            else:
                raise AssertionError('Unexpected fixture read')
            if value is None and not missing:
                raise release.ReleaseFailure('Fixture metadata missing')
            return copy.deepcopy(value)
        if method == 'POST' and path == 'git/refs':
            tag = body['ref'].removeprefix('refs/tags/')
            assert tag not in self.tags
            self.tags[tag] = body['sha']
            return {}
        if method == 'PATCH' and path == 'git/refs/tags/' + release.TAG:
            self.tags[release.TAG] = body['sha']
            return {}
        if method == 'POST' and path == 'releases':
            tag = body['tag_name']; assert tag not in self.releases
            self.next_id += 1
            value = dict(id=self.next_id, assets=[], **copy.deepcopy(body))
            self.releases[tag] = value
            if not body['draft']:
                self.tags.setdefault(tag, body['target_commitish'])
            return copy.deepcopy(value)
        if method == 'DELETE' and path.startswith('releases/'):
            tag = next(tag for tag, value in self.releases.items() if value['id'] == int(path.split('/')[1]))
            del self.releases[tag]
            return None
        raise AssertionError('Unexpected fixture mutation')

    def download(self, tag, name, directory):
        self.calls.append(('DOWNLOAD', tag, name))
        (directory / name).write_bytes(self.data[tag, name])

    def upload(self, tag, files):
        self.calls.append(('UPLOAD', tag, [path.name for path in files]))
        for path in files:
            if tag.startswith('developer-recovery-'):
                assert not any(item['name'] == path.name for item in self.releases[tag]['assets'])
            self.put(tag, path.name, path.read_bytes())
        if self.after_upload:
            self.after_upload(self, tag)

    def verify_public(self, manifest):
        self.calls.append(('PUBLIC_READ', release.TAG, None))
        assert self.releases[release.TAG]['target_commitish'] == manifest['sourceCommit']
        assert hashlib.sha256(self.data[release.TAG, release.APK]).hexdigest() == manifest['apkSha256']

    def publish(self, source=B, code=18, data=None):
        previous = self.tags.get(release.TAG)
        self.add(release.TAG, source, code, data)
        if previous is None:
            self.tags.pop(release.TAG, None)
        else:
            self.tags[release.TAG] = previous


class DeveloperReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apks = self.root / 'apks'; self.apks.mkdir()
        self.evidence = self.root / 'evidence'
        (self.apks / release.APK).write_bytes(apk(18))
        self.receipt = release.inspect(self.apks, B, 400, 1, self.root, 18, sdk)
        (self.apks / 'development-artifact.json').write_text(json.dumps(self.receipt))
        self.manifest = release.make_manifest(self.apks, B, 400, 1, 18, self.root, sdk)
        self.client = GitHubFixture()

    def prepare(self):
        release.write_manifest(self.evidence, self.manifest)
        return release.preflight(self.client, self.manifest, self.root, ancestor=lambda *_: True, sdk_command=sdk)

    def writes(self):
        return [call for call in self.client.calls if call[0] in {'POST', 'PATCH', 'DELETE', 'UPLOAD'}]

    def test_manifest_reuses_actual_artifact_contract_without_claiming_tests_or_installation(self):
        self.assertEqual(B, self.manifest['sourceCommit'])
        self.assertEqual(hashlib.sha256(apk(18)).hexdigest(), self.manifest['apkSha256'])
        self.assertEqual('PENDING', self.manifest['tests'])
        self.assertEqual('PENDING', self.manifest['deviceInstallation'])
        self.assertEqual('APK_PROVENANCE_ONLY', self.manifest['scope'])
        self.assertEqual({'Jarvis-Developer.apk', 'development-artifact.json'}, {p.name for p in self.apks.iterdir()})

    def test_generated_sidecars_are_versioned_separate_and_bound_to_exact_apk(self):
        files = release.write_manifest(self.evidence, self.manifest)
        self.assertEqual(['Developer-0.1.18-verification.json', 'Developer-0.1.18-SHA256SUMS.txt'], [p.name for p in files])
        self.assertEqual(self.manifest, release.read_json(files[0]))
        self.assertEqual(self.manifest['apkSha256'] + '  Jarvis-Developer.apk\n', files[1].read_text())
        self.assertEqual(2, len(list(self.apks.iterdir())))

    def test_unexpected_candidate_files_never_enter_the_existing_job_artifact(self):
        (self.apks / 'unexpected.txt').write_text('private-fixture-password')
        with self.assertRaises(release.ReleaseFailure) as caught:
            release.make_manifest(self.apks, B, 400, 1, 18, self.root, sdk)
        self.assertNotIn('private-fixture-password', str(caught.exception))

    def test_extra_fields_or_invented_test_and_device_results_are_rejected(self):
        for changed in [dict(secret='private-fixture-password'), dict(tests='PASSED'),
                        dict(deviceInstallation='PASSED'), dict(scope='VERIFIED_INSTALLATION')]:
            with self.subTest(changed=changed), self.assertRaises(release.ReleaseFailure):
                release.validate_manifest(dict(self.manifest, **changed))

    def test_bool_and_out_of_range_numeric_fields_are_rejected(self):
        for name in ['schemaVersion', 'buildRunId', 'buildRunAttempt', 'versionCode', 'apkBytes']:
            for value in [True, False, 0, -1, '18', 2**63]:
                with self.subTest(name=name, value=value), self.assertRaises(release.ReleaseFailure):
                    release.validate_manifest(dict(self.manifest, **{name: value}))

    def test_invalid_version_hash_package_or_signer_cannot_become_public_metadata(self):
        for changed in [dict(versionName='../escape'), dict(sourceCommit='main'), dict(apkSha256='x'*64),
                        dict(packageName='com.assistant.core.beta'), dict(signerSha256='e'*64)]:
            with self.subTest(changed=changed), self.assertRaises(release.ReleaseFailure):
                release.validate_manifest(dict(self.manifest, **changed))

    def test_apk_version_receipt_source_and_run_must_match(self):
        with self.assertRaises(release.ReleaseFailure):
            release.make_manifest(self.apks, B, 400, 1, 19, self.root, sdk)
        for key, value in [('sourceCommit', C), ('runId', 401), ('runAttempt', 2)]:
            altered = dict(self.receipt, **{key: value})
            (self.apks / 'development-artifact.json').write_text(json.dumps(altered))
            with self.subTest(key=key), self.assertRaises(release.ReleaseFailure):
                release.make_manifest(self.apks, B, 400, 1, 18, self.root, sdk)

    def test_wrong_or_unsigned_sdk_output_is_sanitized(self):
        for kind in ['package', 'signer', 'throw']:
            def broken(args, text=True):
                if kind == 'throw': raise RuntimeError('private-fixture-password')
                output = sdk(args, text)
                return output.replace('com.jarvis.developer', 'com.assistant.core') if kind == 'package' else output.replace(release.SIGNER, 'e'*64)
            with self.subTest(kind=kind), self.assertRaises(release.ReleaseFailure) as caught:
                release.make_manifest(self.apks, B, 400, 1, 18, self.root, broken)
            self.assertNotIn('private-fixture-password', str(caught.exception))

    def test_symlink_and_extra_apks_are_rejected(self):
        path = self.apks / release.APK; path.unlink(); path.symlink_to(self.root / 'elsewhere')
        with self.assertRaises(release.ReleaseFailure): release.inspect(self.apks, B, 400, 1, self.root, 18, sdk)
        path.unlink(); path.write_bytes(apk(18)); (self.apks / 'other.apk').write_bytes(apk(18))
        with self.assertRaises(release.ReleaseFailure): release.inspect(self.apks, B, 400, 1, self.root, 18, sdk)

    def test_json_duplicates_oversize_and_symlink_destinations_are_rejected(self):
        path = self.root / 'input.json'; path.write_text('{"a":1,"a":2}')
        with self.assertRaises(release.ReleaseFailure): release.read_json(path)
        path.write_text(' ' * 16001)
        with self.assertRaises(release.ReleaseFailure): release.read_json(path)
        path.unlink(); path.symlink_to(self.root / 'elsewhere')
        with self.assertRaises(release.ReleaseFailure): release.write_json(path, {})

    def test_preflight_reads_only_and_checks_the_signed_previous_updater(self):
        plan = self.prepare()
        self.assertEqual('fast-forward', plan['tagMode'])
        self.assertEqual(17, plan['baseline']['version'])
        self.assertEqual([], self.writes())

    def test_missing_asset_digest_and_mismatched_baseline_bytes_fail_closed(self):
        for kind in ['digest', 'bytes', 'source', 'stable']:
            self.client = GitHubFixture()
            meta = self.client.releases[release.TAG]
            if kind == 'digest': meta['assets'][0]['digest'] = None
            if kind == 'bytes': self.client.data[release.TAG, release.APK] = apk(17, 'changed')
            if kind == 'source': meta['target_commitish'] = 'main'
            if kind == 'stable': meta['prerelease'] = False
            with self.subTest(kind=kind), self.assertRaises(release.ReleaseFailure): self.prepare()
            self.assertEqual([], self.writes())

    def test_interrupted_apk_upload_cannot_be_preserved_under_old_release_source_metadata(self):
        # Publication's APK upload succeeded but its following metadata edit did not.
        self.client.put(release.TAG, release.APK, apk(18))
        self.assertEqual('Jarvis Developer 0.1.17', self.client.releases[release.TAG]['name'])
        with self.assertRaisesRegex(release.ReleaseFailure, 'interrupted promotion'):
            release.preserve_recovery(self.client, self.root, sdk)
        self.assertEqual([], self.writes())

    def test_unavailable_previous_source_cannot_be_treated_as_recoverable(self):
        self.client.releases[release.TAG]['target_commitish'] = 'd' * 40
        with self.assertRaises(release.ReleaseFailure):
            release.preserve_recovery(self.client, self.root, sdk)
        self.assertEqual([], self.writes())

    def test_older_or_different_same_version_never_replaces_an_updater(self):
        for code, data in [(19, None), (18, apk(18, 'other'))]:
            self.client.add(release.TAG, B, code, data)
            with self.subTest(code=code), self.assertRaises(release.ReleaseFailure): self.prepare()
            self.assertEqual([], self.writes())

    def test_identical_same_version_can_resume_metadata_verification(self):
        self.client.add(release.TAG, B, 18)
        self.assertEqual('unchanged', self.prepare()['tagMode'])
        self.assertEqual([], self.writes())

    def test_ordinary_divergent_source_cannot_rewind_the_tag(self):
        with self.assertRaises(release.ReleaseFailure):
            release.preflight(self.client, self.manifest, self.root, ancestor=lambda *_: False, sdk_command=sdk)
        self.assertEqual([], self.writes())

    def test_stale_tag_cannot_hide_a_newer_or_divergent_published_source(self):
        self.client.releases[release.TAG]['target_commitish'] = C
        with self.assertRaises(release.ReleaseFailure):
            release.preflight(self.client, self.manifest, self.root,
                              ancestor=lambda older, newer: (older, newer) == (A, B), sdk_command=sdk)
        self.assertEqual([], self.writes())

    def test_explicit_recovery_can_plan_a_non_fast_forward_without_executing_it(self):
        plan = release.preflight(self.client, self.manifest, self.root, recovery=True,
                                 ancestor=lambda *_: False, sdk_command=sdk)
        self.assertEqual('approved-recovery', plan['tagMode'])
        self.assertEqual([], self.writes())

    def test_unsigned_or_rotated_previous_baseline_cannot_be_preserved(self):
        def rotated(args, text=True): return sdk(args, text).replace(release.SIGNER, 'e'*64)
        with self.assertRaises(release.ReleaseFailure): release.preserve_recovery(self.client, self.root, rotated)
        self.assertEqual([], self.writes())

    def test_initial_recovery_is_created_once_and_verified_without_overwrite(self):
        first = release.preserve_recovery(self.client, self.root, sdk)
        self.assertTrue(first['mutationPerformed'])
        self.client.calls.clear()
        second = release.preserve_recovery(self.client, self.root, sdk)
        self.assertFalse(second['mutationPerformed'])
        self.assertEqual([], self.writes())
        self.assertEqual(apk(17), self.client.data[first['tag'], release.APK])

    def test_repeat_publication_from_same_source_keeps_older_snapshot_and_uses_digest_suffix(self):
        legacy = 'developer-recovery-' + A
        self.client.add(legacy, A, 16)
        original = copy.deepcopy(self.client.releases[legacy])
        result = release.preserve_recovery(self.client, self.root, sdk)
        self.assertEqual(legacy + '-' + hashlib.sha256(apk(17)).hexdigest(), result['tag'])
        self.assertEqual(original, self.client.releases[legacy])
        self.assertEqual(apk(16), self.client.data[legacy, release.APK])
        self.assertEqual(apk(17), self.client.data[result['tag'], release.APK])

    def test_mismatched_digest_snapshot_is_not_overwritten(self):
        legacy = 'developer-recovery-' + A
        self.client.add(legacy, A, 16)
        tag = legacy + '-' + hashlib.sha256(apk(17)).hexdigest()
        self.client.add(tag, A, 15)
        original = copy.deepcopy(self.client.releases[tag])
        with self.assertRaises(release.ReleaseFailure): release.preserve_recovery(self.client, self.root, sdk)
        self.assertEqual(original, self.client.releases[tag])
        self.assertEqual([], self.writes())

    def test_existing_recovery_tag_and_metadata_must_keep_the_exact_source(self):
        legacy = 'developer-recovery-' + A
        self.client.tags[legacy] = C
        with self.assertRaises(release.ReleaseFailure): release.preserve_recovery(self.client, self.root, sdk)
        self.assertEqual([], self.writes())

    def test_fresh_baseline_read_prevents_snapshotting_changed_bytes(self):
        original = self.client.download
        count = 0
        def changed(tag, name, directory):
            nonlocal count
            count += 1
            original(tag, name, directory)
            if count == 2: (directory / name).write_bytes(apk(17, 'changed'))
        self.client.download = changed
        with self.assertRaises(release.ReleaseFailure): release.preserve_recovery(self.client, self.root, sdk)
        self.assertEqual([], self.writes())

    def test_finalization_uploads_verified_sidecars_backs_up_source_and_only_fast_forwards(self):
        plan = self.prepare(); self.client.publish()
        result = release.finalize(self.client, self.evidence, self.manifest, plan, ancestor=lambda *_: True)
        self.assertEqual(B, self.client.tags[release.TAG])
        self.assertEqual(A, self.client.tags['developer-source-tag-' + A])
        writes = [call for call in self.writes() if call[0] == 'PATCH']
        self.assertEqual([('PATCH', 'git/refs/tags/developer-latest', {'sha': B, 'force': False})], writes)
        self.assertEqual('PENDING', result['tests']); self.assertEqual('PENDING', result['deviceInstallation'])
        self.assertTrue(result['publicUpdaterDownloadVerified'])

    def test_finalization_is_idempotent_and_never_rewrites_an_immutable_source_backup(self):
        plan = self.prepare(); self.client.publish()
        release.finalize(self.client, self.evidence, self.manifest, plan, ancestor=lambda *_: True)
        self.client.calls.clear()
        release.finalize(self.client, self.evidence, self.manifest, plan, ancestor=lambda *_: True)
        self.assertFalse(any(call[0] in {'POST', 'PATCH', 'DELETE'} for call in self.writes()))

    def test_force_is_only_used_for_the_explicit_recovery_plan(self):
        release.write_manifest(self.evidence, self.manifest)
        plan = release.preflight(self.client, self.manifest, self.root, recovery=True,
                                 ancestor=lambda *_: False, sdk_command=sdk)
        self.client.publish()
        release.finalize(self.client, self.evidence, self.manifest, plan, recovery=True, ancestor=lambda *_: False)
        self.assertEqual([{'sha': B, 'force': True}], [c[2] for c in self.writes() if c[0] == 'PATCH'])

    def test_plan_tampering_or_unapproved_recovery_is_rejected_before_writes(self):
        plan = self.prepare(); self.client.publish()
        for changed in [dict(tagMode='approved-recovery'), dict(recoveryRequested=True),
                        dict(manifest=dict(self.manifest, sourceCommit=C)), dict(schemaVersion=True)]:
            self.client.calls.clear()
            with self.subTest(changed=changed), self.assertRaises(release.ReleaseFailure):
                release.finalize(self.client, self.evidence, self.manifest, dict(plan, **changed), ancestor=lambda *_: True)
            self.assertEqual([], self.writes())

    def test_changed_published_apk_or_tag_prevents_any_evidence_or_ref_write(self):
        for kind in ['tag', 'source', 'bytes']:
            self.client = GitHubFixture(); plan = self.prepare(); self.client.publish()
            if kind == 'tag': self.client.tags[release.TAG] = C
            if kind == 'source': self.client.releases[release.TAG]['target_commitish'] = C
            if kind == 'bytes': self.client.put(release.TAG, release.APK, apk(18, 'different'))
            self.client.calls.clear()
            with self.subTest(kind=kind), self.assertRaises(release.ReleaseFailure):
                release.finalize(self.client, self.evidence, self.manifest, plan, ancestor=lambda *_: True)
            self.assertEqual([], self.writes())

    def test_evidence_mutation_upload_failure_and_tag_races_never_advance_the_tag(self):
        for kind in ['local', 'upload', 'race']:
            self.client = GitHubFixture(); plan = self.prepare(); self.client.publish()
            if kind == 'local': (self.evidence / release.filenames(self.manifest)[1]).write_text('changed')
            if kind == 'upload':
                def fail(*_): raise release.ReleaseFailure('Upload failed')
                self.client.upload = fail
            if kind == 'race': self.client.after_upload = lambda client, _: client.tags.update({release.TAG: C})
            with self.subTest(kind=kind), self.assertRaises(release.ReleaseFailure):
                release.finalize(self.client, self.evidence, self.manifest, plan, ancestor=lambda *_: True)
            self.assertNotIn(B, [c[2].get('sha') for c in self.writes() if c[0] == 'PATCH'])

    def test_read_only_audit_never_constructs_mutations_or_attests_device_installation(self):
        result = release.audit_published(self.client, self.root, sdk)
        self.assertFalse(result['mutationPerformed']); self.assertEqual('PENDING', result['deviceInstallation'])
        self.assertEqual([], self.writes())

    def test_context_denies_wrong_repository_ref_event_and_unapproved_historical_source(self):
        env = dict(GITHUB_REPOSITORY=release.REPO, GITHUB_REF=release.BRANCH, GITHUB_EVENT_NAME='workflow_dispatch',
                   GITHUB_SHA=B, JARVIS_ARTIFACT_SOURCE=B, JARVIS_JOB_RECOVERY_SOURCE='')
        for changed in [dict(GITHUB_REPOSITORY='other/repo'), dict(GITHUB_REF='refs/tags/developer-latest'),
                        dict(GITHUB_REF='refs/heads/feature/jarvis-developer-task/example'), dict(GITHUB_EVENT_NAME='push'),
                        dict(JARVIS_ARTIFACT_SOURCE=A), dict(JARVIS_JOB_RECOVERY_SOURCE=A)]:
            with self.subTest(changed=changed), patch.dict(os.environ, dict(env, **changed), clear=True), \
                    patch.object(release, 'command', return_value=B), patch.object(release, 'ancestry', return_value=True), \
                    self.assertRaises(release.ReleaseFailure): release.context(write=True)

    def test_context_allows_canonical_build_only_preparation_but_not_implicit_recovery(self):
        env = dict(GITHUB_REPOSITORY=release.REPO, GITHUB_REF=release.BRANCH, GITHUB_EVENT_NAME='push',
                   GITHUB_SHA=B, JARVIS_ARTIFACT_SOURCE=B)
        with patch.dict(os.environ, env, clear=True), patch.object(release, 'command', return_value=B), \
                patch.object(release, 'ancestry', return_value=True):
            self.assertEqual((B, False), release.context(write=False))
        with patch.dict(os.environ, dict(env, GITHUB_EVENT_NAME='workflow_dispatch', JARVIS_ARTIFACT_SOURCE=A,
                                        JARVIS_JOB_RECOVERY_SOURCE=A), clear=True), \
                patch.object(release, 'command', return_value=A), patch.object(release, 'ancestry', return_value=True):
            self.assertEqual((A, True), release.context(write=True))

    def test_github_and_sdk_errors_cannot_reflect_private_content(self):
        result = subprocess.CompletedProcess([], 1, '', 'HTTP 401 private-fixture-password')
        with patch.object(release.subprocess, 'run', return_value=result):
            for operation in [lambda: release.GitHub().api('releases/tags/developer-latest'),
                              lambda: release.sdk_output(['aapt'])]:
                with self.assertRaises(release.ReleaseFailure) as caught: operation()
                self.assertNotIn('private-fixture-password', str(caught.exception))

    def test_external_command_deadlines_fail_without_reflecting_credentials_or_retrying_writes(self):
        timeout = subprocess.TimeoutExpired(['fixture'], 60, stderr='private-fixture-password')
        with patch.object(release.subprocess, 'run', side_effect=timeout) as invoked:
            for operation in [lambda: release.GitHub().api('releases', 'POST', {}),
                              lambda: release.sdk_output(['aapt']), lambda: release.ancestry(A, B)]:
                with self.assertRaises(release.ReleaseFailure) as caught: operation()
                self.assertNotIn('private-fixture-password', str(caught.exception))
            self.assertEqual(3, invoked.call_count)

    def test_optimized_python_cannot_bypass_the_reused_artifact_assertions(self):
        code = "import sys; sys.path.insert(0, 'scripts'); import developer_release as r; " \
               "r.inspect(__import__('pathlib').Path('.'), 'a'*40, 1, 1, __import__('pathlib').Path('.'))"
        result = subprocess.run([sys.executable, '-O', '-c', code], text=True, capture_output=True,
                                cwd=Path(__file__).resolve().parents[2])
        self.assertNotEqual(0, result.returncode)
        self.assertIn('assertions enabled', result.stderr)

    def test_public_updater_check_uses_unauthenticated_bounded_exact_bytes(self):
        meta = copy.deepcopy(self.client.releases[release.TAG])
        expected = dict(sourceCommit=A, versionName='0.1.17', apkBytes=len(apk(17)),
                        apkSha256=hashlib.sha256(apk(17)).hexdigest())
        url = 'https://github.com/' + release.REPO + '/releases/download/developer-latest/' + release.APK
        meta['assets'][0]['browser_download_url'] = url
        class Response(io.BytesIO):
            status = 200
            def geturl(self): return url
        responses = [Response(json.dumps(meta).encode()), Response(apk(17))]
        with patch.object(release.urllib.request, 'urlopen', side_effect=responses) as opened:
            release.GitHub().verify_public(expected)
        self.assertEqual(2, opened.call_count)
        for call in opened.call_args_list:
            self.assertNotIn('Authorization', dict(call.args[0].header_items()))

    def test_changed_public_download_and_foreign_asset_url_are_rejected(self):
        for kind in ['bytes', 'url', 'source', 'oversize']:
            meta = copy.deepcopy(self.client.releases[release.TAG])
            url = 'https://github.com/' + release.REPO + '/releases/download/developer-latest/' + release.APK
            meta['assets'][0]['browser_download_url'] = url
            data = apk(17)
            if kind == 'bytes': data = apk(17, 'changed')
            if kind == 'url': meta['assets'][0]['browser_download_url'] = 'https://other.invalid/collect'
            if kind == 'source': meta['target_commitish'] = C
            if kind == 'oversize': data += b'x' * 100
            class Response(io.BytesIO):
                status = 200
                def geturl(self): return url
            expected = dict(sourceCommit=A, versionName='0.1.17', apkBytes=len(apk(17)),
                            apkSha256=hashlib.sha256(apk(17)).hexdigest())
            with self.subTest(kind=kind), patch.object(release.urllib.request, 'urlopen',
                    side_effect=[Response(json.dumps(meta).encode()), Response(data)]), \
                    self.assertRaises(release.ReleaseFailure): release.GitHub().verify_public(expected)

    def test_network_errors_do_not_expose_reflected_credentials(self):
        with patch.object(release.urllib.request, 'urlopen', side_effect=RuntimeError('private-fixture-password')):
            with self.assertRaises(release.ReleaseFailure) as caught:
                release.GitHub().verify_public({})
        self.assertNotIn('private-fixture-password', str(caught.exception))

    def test_draft_exercise_only_uses_unpublished_metadata_and_cleans_its_own_fixture(self):
        env = dict(GITHUB_REPOSITORY=release.REPO, GITHUB_EVENT_NAME='workflow_dispatch',
                   JARVIS_EXERCISE_DRAFT_UPLOAD='true', GITHUB_SHA=B, GITHUB_RUN_ID='400', GITHUB_RUN_ATTEMPT='1')
        previous = copy.deepcopy(self.client.releases[release.TAG])
        with patch.dict(os.environ, env, clear=True):
            result = release.exercise_draft_upload(self.client, self.evidence)
        self.assertTrue(result['draftCreatedAndRemoved']); self.assertFalse(result['apkBuilt'])
        self.assertEqual(previous, self.client.releases[release.TAG])
        self.assertEqual({release.TAG}, set(self.client.releases))

    def test_draft_cleanup_refuses_a_fixture_that_became_public(self):
        env = dict(GITHUB_REPOSITORY=release.REPO, GITHUB_EVENT_NAME='workflow_dispatch',
                   JARVIS_EXERCISE_DRAFT_UPLOAD='true', GITHUB_SHA=B, GITHUB_RUN_ID='400', GITHUB_RUN_ATTEMPT='1')
        self.client.after_upload = lambda client, tag: client.releases[tag].update(draft=False)
        with patch.dict(os.environ, env, clear=True), self.assertRaises(release.ReleaseFailure):
            release.exercise_draft_upload(self.client, self.evidence)
        self.assertFalse(any(call[0] == 'DELETE' for call in self.client.calls))

    def test_draft_upload_failure_cleans_only_its_confirmed_unpublished_fixture(self):
        env = dict(GITHUB_REPOSITORY=release.REPO, GITHUB_EVENT_NAME='workflow_dispatch',
                   JARVIS_EXERCISE_DRAFT_UPLOAD='true', GITHUB_SHA=B, GITHUB_RUN_ID='400', GITHUB_RUN_ATTEMPT='1')
        def fail(*_): raise release.ReleaseFailure('Upload failed')
        self.client.upload = fail
        with patch.dict(os.environ, env, clear=True), self.assertRaises(release.ReleaseFailure):
            release.exercise_draft_upload(self.client, self.evidence)
        self.assertEqual({release.TAG}, set(self.client.releases))

    def test_draft_creation_uncertainty_does_not_guess_or_delete_another_release(self):
        env = dict(GITHUB_REPOSITORY=release.REPO, GITHUB_EVENT_NAME='workflow_dispatch',
                   JARVIS_EXERCISE_DRAFT_UPLOAD='true', GITHUB_SHA=B, GITHUB_RUN_ID='400', GITHUB_RUN_ATTEMPT='1')
        original = self.client.api
        def uncertain(path, method='GET', body=None, missing=False):
            value = original(path, method, body, missing)
            if method == 'POST': raise release.ReleaseFailure('Write uncertain')
            return value
        self.client.api = uncertain
        with patch.dict(os.environ, env, clear=True), self.assertRaises(release.ReleaseFailure):
            release.exercise_draft_upload(self.client, self.evidence)
        self.assertFalse(any(call[0] == 'DELETE' for call in self.client.calls))

    def test_recovery_upload_never_clobbers_and_only_the_exact_apk_is_accepted(self):
        with patch.object(release, 'command') as invoked:
            release.GitHub().upload('developer-recovery-' + A, [self.apks / release.APK])
            self.assertNotIn('--clobber', invoked.call_args[0][0])
        with self.assertRaises(release.ReleaseFailure):
            release.GitHub().upload('developer-recovery-' + A, [self.apks / 'development-artifact.json'])

    def test_existing_candidate_zip_contract_and_manual_publication_boundary_are_retained(self):
        workflow = (Path(__file__).resolve().parents[2] / '.github/workflows/release-jarvis-developer.yml').read_text()
        self.assertIn("github.repository == 'muddigger782012/J.A.R.V.I.S.-' && github.ref == 'refs/heads/feature/jarvis-developer'", workflow)
        self.assertIn('path: ${{ runner.temp }}/developer-job-artifact/', workflow)
        self.assertIn('cp scripts/job_apk_evidence.py scripts/developer_release.py', workflow)
        self.assertNotIn('python3 scripts/job_apk_evidence.py', workflow)
        self.assertIn('developer-release-evidence', workflow)
        self.assertIn('gh release upload developer-latest "$RUNNER_TEMP/developer-job-artifact/Jarvis-Developer.apk"', workflow)
        self.assertNotIn('"$RUNNER_TEMP/Jarvis-Developer.apk"', workflow)
        # All production mutation operations remain explicit-dispatch-only.
        for title in ['Preserve independently verified signed recovery',
                      'Check updater version and source-tag proposal before promotion',
                      'Publish isolated prerelease update', 'Verify public evidence and align the exact source tag']:
            stage = workflow.split('      - name: ' + title + '\n', 1)[1].split('      - ', 1)[0]
            self.assertIn("github.event_name == 'workflow_dispatch'", stage)

    def test_no_secret_probe_uses_the_exact_production_condition_without_invoking_the_signer(self):
        workflows = Path(__file__).resolve().parents[2] / '.github/workflows'
        production = (workflows / 'release-jarvis-developer.yml').read_text()
        validation = (workflows / 'developer-release-provenance-check.yml').read_text()
        def condition(text, job):
            return text.split('  ' + job + ':\n', 1)[1].split('    if: ', 1)[1].splitlines()[0]
        self.assertEqual(condition(production, 'publish'), condition(validation, 'canonical-boundary'))
        self.assertNotIn('secrets.', validation)
        self.assertNotIn('release-jarvis-developer.yml/dispatches', validation)


if __name__ == '__main__':
    unittest.main()
