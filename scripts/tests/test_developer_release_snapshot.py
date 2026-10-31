"""Explicit GitHub/SDK fixtures; no production writes or device claims."""
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import test_developer_release as fixtures

release = fixtures.release
A, B, C = fixtures.A, fixtures.B, fixtures.C
APK_HASH = hashlib.sha256(fixtures.apk(17)).hexdigest()
ENV = dict(GITHUB_REPOSITORY=release.REPO, GITHUB_REF=release.BRANCH,
           GITHUB_EVENT_NAME='workflow_dispatch', GITHUB_SHA=B, GITHUB_RUN_ID='123',
           GITHUB_RUN_ATTEMPT='1', ANDROID_HOME='/explicit-modeled-sdk')


class ApprovedRecoverySnapshotTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.client = fixtures.GitHubFixture()
        for mock in [patch.dict(os.environ, ENV, clear=True),
                     patch.object(release, 'command', return_value=B),
                     patch.object(release, 'ancestry', return_value=True)]:
            mock.start()
            self.addCleanup(mock.stop)

    def preserve(self, source=A, apk_hash=APK_HASH, approve=True):
        return release.preserve_approved_recovery(self.client, self.root, source, apk_hash, approve, fixtures.sdk)

    def writes(self):
        return [c for c in self.client.calls if c[0] in {'POST', 'PATCH', 'DELETE', 'UPLOAD'}]

    def test_readonly_plan_binds_actual_fixture_source_bytes_and_pinned_identity(self):
        value = release.recovery_plan(self.client, self.root, fixtures.sdk)
        self.assertEqual('APPROVAL_REQUIRED', value['status'])
        self.assertEqual(dict(source=A, version=17, sha256=APK_HASH, bytes=len(fixtures.apk(17))), value['baseline'])
        self.assertEqual('developer-recovery-' + A, value['destinationTag'])
        self.assertEqual('com.jarvis.developer', value['packageName'])
        self.assertEqual(release.SIGNER, value['signerSha256'])
        self.assertEqual('NOT_ASSESSED', value['promotionEligibility'])
        self.assertEqual('PENDING', value['deviceInstallation'])
        self.assertFalse(value['mutationPerformed'])
        self.assertFalse(value['signingCredentialsUsed'])
        self.assertEqual([], self.writes())

    def test_exact_owner_approval_creates_one_prerelease_and_fixed_asset_only(self):
        result = self.preserve()
        self.assertEqual('VERIFIED', result['status'])
        self.assertTrue(result['mutationPerformed'])
        self.assertEqual('NOT_ASSESSED', result['promotionEligibility'])
        self.assertEqual(['POST', 'UPLOAD'], [c[0] for c in self.writes()])
        body = self.writes()[0][2]
        self.assertEqual('developer-recovery-' + A, body['tag_name'])
        self.assertEqual(A, body['target_commitish'])
        self.assertIs(False, body['draft'])
        self.assertIs(True, body['prerelease'])
        self.assertEqual('false', body['make_latest'])
        self.assertEqual([release.APK], self.writes()[1][2])
        self.assertEqual(fixtures.apk(17), self.client.data[result['destinationTag'], release.APK])

    def test_missing_malformed_or_nonboolean_approval_never_reaches_repository(self):
        for change in [dict(approve=False), dict(approve='true'), dict(approve=1),
                       dict(source='main'), dict(source=None), dict(apk_hash='invalid'), dict(apk_hash=None)]:
            self.client.calls.clear()
            with self.subTest(change=change), self.assertRaises(release.ReleaseFailure):
                self.preserve(**change)
            self.assertEqual([], self.client.calls)

    def test_push_pull_request_wrong_branch_and_wrong_repository_cannot_write(self):
        for change in [dict(GITHUB_EVENT_NAME='push'), dict(GITHUB_EVENT_NAME='pull_request'),
                       dict(GITHUB_REF='refs/heads/feature/jarvis-developer-task/fixture'),
                       dict(GITHUB_REPOSITORY='someone/another-repository')]:
            with patch.dict(os.environ, change), self.subTest(change=change), self.assertRaises(release.ReleaseFailure):
                self.preserve()
            self.assertEqual([], self.client.calls)

    def test_stale_controller_and_historical_source_overrides_cannot_borrow_approval(self):
        for change in [dict(GITHUB_SHA=C), dict(JARVIS_JOB_RECOVERY_SOURCE=A), dict(JARVIS_ARTIFACT_SOURCE=A)]:
            with patch.dict(os.environ, change), self.subTest(change=change), self.assertRaises(release.ReleaseFailure):
                self.preserve()
            self.assertEqual([], self.client.calls)

    def test_exact_source_and_apk_approval_is_not_transferable(self):
        for change in [dict(source=C), dict(apk_hash='e'*64)]:
            with self.subTest(change=change), self.assertRaisesRegex(release.ReleaseFailure, 'fresh approval'):
                self.preserve(**change)
            self.assertEqual([], self.writes())

    def test_unrelated_source_history_is_rejected_without_writes(self):
        with patch.object(release, 'ancestry', return_value=False), self.assertRaises(release.ReleaseFailure):
            self.preserve()
        self.assertEqual([], self.writes())

    def test_existing_exact_snapshot_is_verified_without_reupload_or_new_approval_authority(self):
        self.preserve()
        self.client.calls.clear()
        value = self.preserve()
        self.assertFalse(value['mutationPerformed'])
        self.assertEqual([], self.writes())
        self.assertEqual('NOT_ASSESSED', value['promotionEligibility'])
        self.assertTrue(any(c[0] == 'DOWNLOAD' and c[1] == value['destinationTag'] for c in self.client.calls))

    def test_stale_legacy_and_older_source_snapshot_are_preserved_unchanged(self):
        self.client.add('developer-previous', C, 10)
        self.client.add('developer-recovery-' + A, A, 16)
        before_releases = copy.deepcopy(self.client.releases)
        before_data = copy.deepcopy(self.client.data)
        result = self.preserve()
        self.assertEqual('developer-recovery-' + A + '-' + APK_HASH, result['destinationTag'])
        for tag, value in before_releases.items():
            self.assertEqual(value, self.client.releases[tag])
        for key, value in before_data.items():
            self.assertEqual(value, self.client.data[key])

    def test_missing_current_release_and_corrupt_existing_destination_fail_closed(self):
        for kind in ['missing', 'digest', 'ref', 'bytes', 'partial']:
            self.client = fixtures.GitHubFixture(current=kind != 'missing')
            tag = 'developer-recovery-' + A
            if kind != 'missing':
                self.client.add(tag, A, 17)
                if kind == 'digest': self.client.releases[tag]['assets'][0]['digest'] = None
                if kind == 'ref': self.client.tags[tag] = C
                if kind == 'bytes': self.client.data[tag, release.APK] = fixtures.apk(17, 'corrupt')
                if kind == 'partial': self.client.releases[tag]['assets'] = []
            with self.subTest(kind=kind), self.assertRaises(release.ReleaseFailure): self.preserve()
            self.assertEqual([], self.writes())

    def test_current_release_changed_between_plan_and_preservation_needs_new_approval(self):
        original = release.recovery_plan
        def drift(*args):
            value = original(*args)
            self.client.publish(B, 18)
            return value
        with patch.object(release, 'recovery_plan', side_effect=drift), self.assertRaisesRegex(release.ReleaseFailure, 'fresh approval'):
            self.preserve()
        self.assertEqual([], self.writes())

    def test_same_source_rebuilt_bytes_are_not_covered_by_earlier_approval(self):
        self.client.publish(A, 18)
        with self.assertRaisesRegex(release.ReleaseFailure, 'fresh approval'): self.preserve()
        self.assertEqual([], self.writes())

    def test_receipt_reports_actual_digest_destination_if_older_snapshot_appears_after_plan(self):
        original = release.recovery_plan
        def appeared(*args):
            value = original(*args)
            self.client.add('developer-recovery-' + A, A, 16)
            return value
        with patch.object(release, 'recovery_plan', side_effect=appeared):
            value = self.preserve()
        self.assertEqual('developer-recovery-' + A + '-' + APK_HASH, value['destinationTag'])
        self.assertEqual(fixtures.apk(17), self.client.data[value['destinationTag'], release.APK])

    def test_current_release_changes_immediately_before_create_stop_all_writes(self):
        original = self.client.download
        latest_reads = 0
        def drift(tag, name, directory):
            nonlocal latest_reads
            original(tag, name, directory)
            if tag == release.TAG:
                latest_reads += 1
                if latest_reads == 4: self.client.publish(B, 18)
        self.client.download = drift
        with self.assertRaisesRegex(release.ReleaseFailure, 'before recovery creation'): self.preserve()
        self.assertEqual([], self.writes())

    def test_destination_appearing_during_prewrite_recheck_is_not_overwritten(self):
        original = self.client.download
        latest_reads = 0
        def race(tag, name, directory):
            nonlocal latest_reads
            original(tag, name, directory)
            if tag == release.TAG:
                latest_reads += 1
                if latest_reads == 5: self.client.add('developer-recovery-' + A, A, 17)
        self.client.download = race
        with self.assertRaisesRegex(release.ReleaseFailure, 'destination changed'): self.preserve()
        self.assertEqual([], self.writes())

    def test_uncertain_create_stops_once_without_upload_retry_cleanup_or_leaking_error(self):
        original = self.client.api
        def failed(path, method='GET', body=None, missing=False):
            result = original(path, method, body, missing)
            if method == 'POST': raise OSError('EXPLICIT_PRIVATE_FIXTURE')
            return result
        self.client.api = failed
        with self.assertRaisesRegex(release.ReleaseFailure, 'outcome is uncertain') as caught: self.preserve()
        self.assertNotIn('EXPLICIT_PRIVATE_FIXTURE', str(caught.exception))
        self.assertEqual(['POST'], [c[0] for c in self.writes()])
        self.assertIn('developer-recovery-' + A, self.client.releases)

    def test_mismatched_create_response_never_uploads_and_retains_state_for_review(self):
        original = self.client.api
        def wrong(path, method='GET', body=None, missing=False):
            result = original(path, method, body, missing)
            return dict(result, target_commitish=C) if method == 'POST' else result
        self.client.api = wrong
        with self.assertRaisesRegex(release.ReleaseFailure, 'outcome is uncertain'): self.preserve()
        self.assertEqual(['POST'], [c[0] for c in self.writes()])

    def test_uncertain_upload_has_no_automatic_retry_and_partial_release_cannot_be_repaired_silently(self):
        def failed(tag, files):
            self.client.calls.append(('UPLOAD', tag, [p.name for p in files]))
            raise OSError('EXPLICIT_PRIVATE_FIXTURE')
        self.client.upload = failed
        with self.assertRaisesRegex(release.ReleaseFailure, 'outcome is uncertain'): self.preserve()
        self.assertEqual(['POST', 'UPLOAD'], [c[0] for c in self.writes()])
        self.client.calls.clear()
        with self.assertRaises(release.ReleaseFailure): self.preserve()
        self.assertEqual([], self.writes())

    def test_postwrite_byte_ref_or_current_release_drift_never_claims_success(self):
        for kind in ['bytes', 'ref', 'latest']:
            self.client = fixtures.GitHubFixture()
            def changed(client, tag):
                if kind == 'bytes': client.data[tag, release.APK] = fixtures.apk(17, 'changed')
                if kind == 'ref': client.tags[tag] = C
                if kind == 'latest': client.publish(B, 18)
            self.client.after_upload = changed
            with self.subTest(kind=kind), self.assertRaisesRegex(release.ReleaseFailure, 'outcome is uncertain'):
                self.preserve()
            self.assertEqual(['POST', 'UPLOAD'], [c[0] for c in self.writes()])

    def test_readonly_pull_request_controller_cannot_be_reused_for_preservation(self):
        with patch.dict(os.environ, GITHUB_EVENT_NAME='pull_request', GITHUB_REF='refs/pull/999/merge'):
            self.assertEqual(B, release.recovery_context())
            with self.assertRaises(release.ReleaseFailure): self.preserve()
        self.assertEqual([], self.writes())

    def test_cli_readonly_plan_runs_without_candidate_artifact_and_emits_no_approval(self):
        output = self.root / 'evidence'
        original = release.recovery_plan
        with patch.object(release, 'GitHub', return_value=self.client), \
             patch.object(release, 'recovery_plan', side_effect=lambda client, sdk: original(client, sdk, fixtures.sdk)), \
             patch('sys.argv', ['developer_release.py', 'audit-recovery-plan', '--evidence', str(output)]), \
             patch('sys.stdout', new_callable=io.StringIO) as stdout:
            self.assertEqual(0, release.main())
        value = json.loads((output / 'recovery-snapshot-plan.json').read_text())
        self.assertEqual(B, value['controllerCommitSha'])
        self.assertEqual('APPROVAL_REQUIRED', value['status'])
        self.assertEqual([], self.writes())
        self.assertNotIn('EXPLICIT_PRIVATE_FIXTURE', stdout.getvalue())


    def test_recovery_ref_changing_during_download_cannot_pass_readonly_verification(self):
        tag = 'developer-recovery-' + A
        self.client.add(tag, A, 17)
        original = self.client.download
        def drift(selected, name, directory):
            original(selected, name, directory)
            if selected == tag: self.client.tags[tag] = C
        self.client.download = drift
        with self.assertRaisesRegex(release.ReleaseFailure, 'during readback'):
            release.recovery_plan(self.client, self.root, fixtures.sdk)
        self.assertEqual([], self.writes())

    def test_download_counter_changes_do_not_replace_or_invalidate_exact_asset_identity(self):
        tag = 'developer-recovery-' + A
        self.client.add(tag, A, 17)
        original = self.client.download
        def counted(selected, name, directory):
            original(selected, name, directory)
            if selected == tag: self.client.releases[tag]['assets'][0]['download_count'] = 1
        self.client.download = counted
        self.assertEqual('VERIFIED', release.recovery_plan(self.client, self.root, fixtures.sdk)['status'])
        self.assertEqual([], self.writes())

    def test_cli_uncertain_write_records_blocked_unknown_state_without_secret_or_retry(self):
        output = self.root / 'evidence'
        with patch.object(release, 'preserve_approved_recovery', side_effect=release.ReleaseFailure(
                'Recovery write outcome is uncertain; retain state and run a fresh read-only audit before any retry')), \
             patch('sys.argv', ['developer_release.py', 'preserve-approved-recovery', '--evidence', str(output)]), \
             patch('sys.stdout', new_callable=io.StringIO):
            self.assertEqual(1, release.main())
        value = json.loads((output / 'recovery-snapshot-plan.json').read_text())
        self.assertEqual('BLOCKED', value['status'])
        self.assertEqual('UNKNOWN', value['mutationPerformed'])
        self.assertEqual('NOT_ASSESSED', value['promotionEligibility'])
        self.assertTrue(value['requiresFreshAudit'])
        self.assertNotIn('baseline', value)
        self.assertEqual([], self.writes())

    def test_local_receipt_failure_after_success_never_reports_that_no_mutation_occurred(self):
        output = self.root / 'evidence'
        real_write = release.write_json
        writes = 0
        def failed_once(path, data):
            nonlocal writes
            writes += 1
            if writes == 1: raise OSError('EXPLICIT_PRIVATE_FIXTURE')
            real_write(path, data)
        with patch.object(release, 'preserve_approved_recovery', return_value=dict(mutationPerformed=True)), \
             patch.object(release, 'write_json', side_effect=failed_once), \
             patch('sys.argv', ['developer_release.py', 'preserve-approved-recovery', '--evidence', str(output)]), \
             patch('sys.stdout', new_callable=io.StringIO) as stdout:
            self.assertEqual(1, release.main())
        value = json.loads((output / 'recovery-snapshot-plan.json').read_text())
        self.assertEqual('BLOCKED', value['status'])
        self.assertIs(True, value['mutationPerformed'])
        self.assertNotIn('EXPLICIT_PRIVATE_FIXTURE', stdout.getvalue())

    def test_unexpected_preservation_cleanup_failure_does_not_invent_a_no_write_result(self):
        output = self.root / 'evidence'
        with patch.object(release, 'preserve_approved_recovery', side_effect=OSError('EXPLICIT_PRIVATE_FIXTURE')), \
             patch('sys.argv', ['developer_release.py', 'preserve-approved-recovery', '--evidence', str(output)]), \
             patch('sys.stdout', new_callable=io.StringIO) as stdout:
            self.assertEqual(1, release.main())
        value = json.loads((output / 'recovery-snapshot-plan.json').read_text())
        self.assertEqual('UNKNOWN', value['mutationPerformed'])
        self.assertEqual('BLOCKED', value['status'])
        self.assertNotIn('EXPLICIT_PRIVATE_FIXTURE', stdout.getvalue())
