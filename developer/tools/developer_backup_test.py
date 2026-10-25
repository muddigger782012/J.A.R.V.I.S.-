"""Actual fixture bytes; modeled GitHub and fixed Android SDK output, no network."""
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import zipfile

import candidate_audit as audit
import developer_backup as backup

SHA = "a" * 40
OTHER = "b" * 40
CERT = "c" * 64
ENV = dict(GITHUB_EVENT_NAME="workflow_dispatch", GITHUB_REF="refs/heads/" + audit.BRANCH,
           GITHUB_REPOSITORY=audit.REPO, ANDROID_HOME="/modeled-sdk", GITHUB_SHA=OTHER,
           GITHUB_RUN_ID="123", GITHUB_RUN_ATTEMPT="1", GH_TOKEN="never-print-this-token")


def apk_fixture(version=53, certificate=CERT, package=audit.PACKAGE, payload="exact approved fixture"):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        archive.writestr("AndroidManifest.xml", json.dumps(dict(version=version, certificate=certificate, package=package, payload=payload)))
    return output.getvalue()


def modeled_sdk(args):
    with zipfile.ZipFile(args[-1]) as archive:
        value = json.loads(archive.read("AndroidManifest.xml"))
    if args[0].endswith("aapt"):
        return f"package: name='{value['package']}' versionCode='{value['version']}' versionName='modeled'\n"
    return "Signer #1 certificate SHA-256 digest: " + value["certificate"] + "\n"


class ModeledRead:
    def __init__(self):
        self.data = apk_fixture()
        self.source = SHA
        self.previous_data = None
        self.previous_source = None
        self.previous_tag = None
        self.latest_tag = SHA
        self.calls = []
        self.latest_reads = 0
        self.on_latest = None

    def get(self, path, **kwargs):
        self.calls.append(path)
        if path == backup.PREFIX + "/releases/tags/developer-latest":
            self.latest_reads += 1
            if self.on_latest:
                self.on_latest(self)
            return self.release("developer-latest", self.source, self.data, 1)
        if path == backup.PREFIX + "/releases/tags/developer-previous":
            return None if self.previous_data is None else self.release("developer-previous", self.previous_source, self.previous_data, 2)
        if path == backup.PREFIX + "/releases/assets/1":
            return self.data
        if path == backup.PREFIX + "/releases/assets/2":
            return self.previous_data
        if path.startswith(backup.PREFIX + "/git/ref/tags/"):
            tag = path.rsplit("/", 1)[1]
            value = self.latest_tag if tag == "developer-latest" else self.previous_tag
            return None if value is None else dict(ref="refs/tags/" + tag, object=dict(type="commit", sha=value))
        if path == backup.PREFIX + "/commits/" + self.source:
            return dict(sha=self.source)
        raise AssertionError("Unexpected modeled GET: " + path)

    @staticmethod
    def release(tag, source, data, asset_id):
        return dict(tag_name=tag, target_commitish=source, draft=False, prerelease=True,
                    assets=[dict(name="Jarvis-Developer.apk", id=asset_id, size=len(data), digest="sha256:" + hashlib.sha256(data).hexdigest())])


class ModeledWriter:
    def __init__(self, api):
        self.api = api
        self.calls = []
        self.fail_stage = None
        self.after_upload = None

    def create_backup(self, source):
        self.calls.append(("create", source))
        if self.fail_stage == "create":
            raise backup.BackupFailure("GITHUB_WRITE_HTTP_403")
        self.api.previous_source = source
        self.api.previous_tag = source
        return 123

    def upload_apk(self, release_id, data):
        self.calls.append(("upload", release_id))
        if self.fail_stage == "upload":
            raise backup.BackupFailure("GITHUB_WRITE_HTTP_401")
        self.api.previous_data = data
        if self.after_upload:
            self.after_upload(self.api)


class DeveloperBackupTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.api = ModeledRead()
        self.writer = ModeledWriter(self.api)
        self.env = patch.dict(os.environ, ENV)
        self.env.start()
        self.addCleanup(self.env.stop)
        real_inspect = audit.inspect_apk
        self.inspect = patch.object(audit, "inspect_apk", side_effect=lambda path, source: real_inspect(path, source, command=modeled_sdk))
        self.inspect.start()
        self.addCleanup(self.inspect.stop)

    def preserve(self, source=SHA, digest=None, approve=True):
        return backup.preserve(self.api, self.writer, self.root, source, digest or hashlib.sha256(self.api.data).hexdigest(), approve)

    def test_readonly_plan_verifies_actual_fixture_bytes_and_never_writes(self):
        value = backup.plan(self.api, self.root)
        self.assertEqual(value["status"], "APPROVAL_REQUIRED")
        self.assertEqual(value["previousStatus"], "MISSING")
        self.assertEqual(value["baseline"]["apkSha256"], hashlib.sha256(self.api.data).hexdigest())
        self.assertEqual(value["baseline"]["apkBytes"], len(self.api.data))
        self.assertEqual(value["baseline"]["signerSha256"], CERT)
        self.assertEqual(value["baseline"]["sourceCommitSha"], SHA)
        self.assertFalse(value["mutationPerformed"])
        self.assertEqual(self.writer.calls, [])

    def test_exact_manual_approval_creates_once_then_retrieves_verified_bytes(self):
        result = self.preserve()
        self.assertEqual(self.writer.calls, [("create", SHA), ("upload", 123)])
        self.assertTrue(result["mutationPerformed"])
        self.assertEqual(result["status"], "VERIFIED")
        self.assertTrue(audit.same_backup(result["baseline"], result["backup"]))
        self.assertEqual((self.root / "developer-previous.apk").read_bytes(), self.api.data)
        self.assertEqual(self.api.latest_reads, 3)
        again = self.preserve()
        self.assertFalse(again["mutationPerformed"])
        self.assertEqual(len(self.writer.calls), 2)

    def test_manual_boolean_and_full_sha_and_hash_are_required_before_reads_or_writes(self):
        for source, digest, approve in ((SHA, "d" * 64, False), (SHA[:12], "d" * 64, True),
                                        (SHA.upper(), "d" * 64, True), (SHA, "d" * 63, True),
                                        (SHA, "D" * 64, True), (SHA, "d" * 64, "true")):
            with self.subTest(source=source, digest=digest, approve=approve), self.assertRaises(backup.BackupFailure):
                self.preserve(source, digest, approve)
        self.assertEqual(self.api.calls, [])
        self.assertEqual(self.writer.calls, [])

    def test_push_wrong_branch_or_wrong_repository_cannot_preserve(self):
        for key, value in (("GITHUB_EVENT_NAME", "push"), ("GITHUB_REF", "refs/heads/main"),
                           ("GITHUB_REPOSITORY", "other/repo")):
            with self.subTest(key=key), patch.dict(os.environ, {key: value}), self.assertRaises(backup.BackupFailure):
                self.preserve()
        self.assertEqual(self.api.calls, [])
        self.assertEqual(self.writer.calls, [])

    def test_mismatched_approved_source_or_hash_blocks_without_mutation(self):
        for source, digest in ((OTHER, None), (SHA, "d" * 64)):
            with self.subTest(source=source, digest=digest), self.assertRaisesRegex(backup.BackupFailure, "APPROVED_SOURCE_OR_APK_CHANGED"):
                self.preserve(source, digest)
        self.assertEqual(self.writer.calls, [])

    def test_moving_latest_tag_is_disclosed_but_never_followed_as_published_source(self):
        self.api.latest_tag = OTHER
        result = self.preserve()
        self.assertEqual(result["baseline"]["latestTagSource"], OTHER)
        self.assertFalse(result["baseline"]["latestTagMatchesRecordedSource"])
        self.assertEqual(result["baseline"]["sourceCommitSha"], SHA)
        self.assertEqual(self.api.previous_tag, SHA)
        self.assertEqual(self.writer.calls[0], ("create", SHA))
        self.assertIn(backup.PREFIX + "/commits/" + SHA, self.api.calls)
        self.assertNotIn(backup.PREFIX + "/commits/" + OTHER, self.api.calls)

    def test_changed_latest_bytes_or_source_before_create_blocks(self):
        for change in ("bytes", "source"):
            with self.subTest(change=change):
                self.api = ModeledRead()
                self.writer = ModeledWriter(self.api)
                def change_latest(api):
                    if api.latest_reads == 2:
                        if change == "bytes":
                            api.data = apk_fixture(payload="changed publication")
                        else:
                            api.source = OTHER
                            api.latest_tag = OTHER
                self.api.on_latest = change_latest
                with self.assertRaisesRegex(backup.BackupFailure, "LATEST_CHANGED_BEFORE_CREATE"):
                    self.preserve()
                self.assertEqual(self.writer.calls, [])

    def test_existing_exact_backup_is_verified_without_a_write(self):
        self.api.previous_data = self.api.data
        self.api.previous_source = self.api.previous_tag = SHA
        result = self.preserve()
        self.assertEqual(result["previousStatus"], "VERIFIED")
        self.assertFalse(result["mutationPerformed"])
        self.assertEqual(self.writer.calls, [])

    def test_existing_old_bytes_source_signer_or_tag_is_never_overwritten(self):
        for change in ("bytes", "source", "signer", "tag"):
            with self.subTest(change=change):
                self.api.previous_data = self.api.data
                self.api.previous_source = self.api.previous_tag = SHA
                if change == "bytes":
                    self.api.previous_data = apk_fixture(payload="old backup")
                if change == "source":
                    self.api.previous_source = OTHER
                if change == "signer":
                    self.api.previous_data = apk_fixture(certificate="e" * 64)
                if change == "tag":
                    self.api.previous_tag = OTHER
                with self.assertRaisesRegex(backup.BackupFailure, "EXISTING_BACKUP_MISMATCH_NO_OVERWRITE"):
                    self.preserve()
        self.assertEqual(self.writer.calls, [])

    def test_orphan_existing_tag_blocks_instead_of_reusing_or_updating_it(self):
        self.api.previous_tag = SHA
        with self.assertRaisesRegex(backup.BackupFailure, "PREVIOUS_TAG_EXISTS_WITHOUT_RELEASE"):
            self.preserve()
        self.assertEqual(self.writer.calls, [])

    def test_concurrently_created_exact_backup_avoids_any_write(self):
        def race(api):
            if api.latest_reads == 2:
                api.previous_data = api.data
                api.previous_source = api.previous_tag = SHA
        self.api.on_latest = race
        result = self.preserve()
        self.assertEqual(result["status"], "VERIFIED")
        self.assertEqual(self.writer.calls, [])

    def test_after_upload_latest_race_is_uncertain_and_never_deleted_or_retried(self):
        self.writer.after_upload = lambda api: setattr(api, "data", apk_fixture(payload="racing publication"))
        with self.assertRaisesRegex(backup.BackupFailure, "WRITE_UNCERTAIN_LATEST_CHANGED_AFTER_CREATE"):
            self.preserve()
        self.assertEqual(self.writer.calls, [("create", SHA), ("upload", 123)])
        self.assertIsNotNone(self.api.previous_data)

    def test_partial_create_and_upload_access_failures_are_explicit_and_not_retried(self):
        for stage, code in (("create", "403"), ("upload", "401")):
            with self.subTest(stage=stage):
                self.api = ModeledRead()
                self.writer = ModeledWriter(self.api)
                self.writer.fail_stage = stage
                with self.assertRaisesRegex(backup.BackupFailure, "WRITE_UNCERTAIN_GITHUB_WRITE_HTTP_" + code):
                    self.preserve()
                self.assertEqual(len(self.writer.calls), 1 if stage == "create" else 2)

    def test_racing_wrong_tag_after_create_blocks_before_upload_without_cleanup(self):
        create = self.writer.create_backup
        def racing_create(source):
            result = create(source)
            self.api.previous_tag = OTHER
            return result
        self.writer.create_backup = racing_create
        with self.assertRaisesRegex(backup.BackupFailure, "WRITE_UNCERTAIN_CREATED_TAG_SOURCE_MISMATCH"):
            self.preserve()
        self.assertEqual(self.writer.calls, [("create", SHA)])
        self.assertIsNone(self.api.previous_data)

    def test_failed_readback_does_not_report_verified_or_clean_up(self):
        self.writer.after_upload = lambda api: setattr(api, "previous_data", apk_fixture(payload="wrong uploaded bytes"))
        with self.assertRaisesRegex(backup.BackupFailure, "WRITE_UNCERTAIN_EXISTING_BACKUP_MISMATCH_NO_OVERWRITE"):
            self.preserve()
        self.assertEqual(len(self.writer.calls), 2)

    def test_apk_package_and_signed_archive_validation_fail_closed(self):
        for data in (apk_fixture(package="com.jarvis.beta"), b"not an APK"):
            self.api.data = data
            with self.assertRaises(audit.AuditFailure):
                self.preserve()
        self.assertEqual(self.writer.calls, [])

    def test_cli_audit_never_constructs_writer_even_with_approval_environment(self):
        args = ["developer_backup.py", "audit", "--controller", ".", "--directory", str(self.root)]
        with patch.object(sys, "argv", args), patch.object(backup, "context"), \
                patch.object(backup, "FixedRead", return_value=self.api), \
                patch.object(backup, "FixedWriter", side_effect=AssertionError("Audit constructed a writer")), \
                patch.dict(os.environ, {"APPROVE_BACKUP": "true", "APPROVED_SOURCE_SHA": SHA,
                                        "APPROVED_APK_SHA256": hashlib.sha256(self.api.data).hexdigest()}), \
                patch("sys.stdout", new_callable=io.StringIO) as output:
            self.assertEqual(backup.main(), 0)
        result = json.loads((self.root / "backup-plan.json").read_bytes())
        self.assertEqual(result["previousStatus"], "MISSING")
        self.assertIn("JARVIS_BACKUP_PLAN=", output.getvalue())
        self.assertNotIn(ENV["GH_TOKEN"], output.getvalue())
        self.assertLess(len(output.getvalue()), 8_500)

    def test_fixed_read_access_failures_preserve_only_http_status(self):
        for status in (401, 403):
            reader = backup.FixedRead(ENV["GH_TOKEN"], ".")
            def fail(*args, **kwargs):
                raise urllib.error.HTTPError("https://api.github.com/private?token=hidden", status,
                                             "secret body must never leak", {}, io.BytesIO(b"sensitive body"))
            reader.opener.opener.open = fail
            with self.assertRaisesRegex(backup.BackupFailure, "^GITHUB_READ_HTTP_" + str(status) + "$"):
                reader.get(backup.PREFIX + "/releases/tags/developer-latest")

    def test_actual_temporary_git_history_requires_baseline_ancestor_of_controller(self):
        controller = self.root / "controller"
        controller.mkdir()
        audit.git(["init", "-b", "trusted"], controller)
        def commit(message):
            audit.git(["-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                       "commit", "--allow-empty", "-m", message], controller)
            return audit.git(["rev-parse", "HEAD"], controller)
        baseline = commit("modeled known-good source")
        trusted = commit("modeled trusted controller")
        audit.git(["checkout", "--orphan", "unrelated"], controller)
        unrelated = commit("unrelated source")
        reader = backup.FixedRead(ENV["GH_TOKEN"], controller)
        class Response:
            status = 200
            def __init__(self, value): self.value = value
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def read(self, size): return audit.encoded(self.value)
        reader.opener.opener.open = lambda request, timeout: Response(dict(sha=request.full_url.rsplit("/", 1)[1]))
        with patch.dict(os.environ, {"GITHUB_SHA": trusted}):
            self.assertEqual(reader.get(backup.PREFIX + "/commits/" + baseline)["sha"], baseline)
            with self.assertRaisesRegex(backup.BackupFailure, "BASELINE_NOT_CONTROLLER_ANCESTOR"):
                reader.get(backup.PREFIX + "/commits/" + unrelated)

    def test_malformed_write_response_is_fixed_failure_and_no_upload(self):
        writer = backup.FixedWriter(ENV["GH_TOKEN"])
        class Response:
            status = 201
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def read(self, size): return b"[]"
        writer.opener.open = lambda *args, **kwargs: Response()
        with self.assertRaisesRegex(backup.BackupFailure, "GITHUB_WRITE_RESPONSE_INVALID"):
            writer.create_backup(SHA)
        self.assertTrue(writer.create_attempted)
        self.assertFalse(writer.upload_attempted)

    def test_malformed_after_write_readback_remains_uncertain(self):
        def malformed(api):
            api.previous_data = ["malformed"]
        self.writer.after_upload = malformed
        with self.assertRaisesRegex(backup.BackupFailure, "WRITE_UNCERTAIN"):
            self.preserve()
        self.assertEqual(len(self.writer.calls), 2)

    def test_writer_has_fixed_create_upload_hosts_and_no_clobber_or_retry(self):
        writer = backup.FixedWriter(ENV["GH_TOKEN"])
        requests = []
        class Response:
            status = 201
            def __init__(self, value): self.value = value
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def read(self, size): return audit.encoded(self.value)
        def request(value, timeout):
            requests.append(value)
            if len(requests) == 1:
                return Response(dict(id=321, tag_name="developer-previous", target_commitish=SHA, draft=False,
                                     prerelease=True, upload_url="https://malicious.invalid/ignored"))
            return Response(dict(id=456, name="Jarvis-Developer.apk", size=len(self.api.data)))
        writer.opener.open = request
        self.assertEqual(writer.create_backup(SHA), 321)
        writer.upload_apk(321, self.api.data)
        self.assertEqual([item.method for item in requests], ["POST", "POST"])
        self.assertEqual(requests[0].full_url, "https://api.github.com" + backup.PREFIX + "/releases")
        self.assertEqual(json.loads(requests[0].data)["make_latest"], "false")
        self.assertEqual(requests[1].full_url, "https://uploads.github.com" + backup.PREFIX + "/releases/321/assets?name=Jarvis-Developer.apk")
        with self.assertRaises(backup.BackupFailure): writer.create_backup(SHA)
        with self.assertRaises(backup.BackupFailure): writer.upload_apk(321, self.api.data)
        self.assertEqual(len(requests), 2)

    def test_write_403_is_fixed_status_without_response_body_or_token(self):
        writer = backup.FixedWriter(ENV["GH_TOKEN"])
        def denied(*args, **kwargs):
            raise urllib.error.HTTPError("https://api.github.com", 403, "secret details", {}, io.BytesIO(b"raw sensitive body"))
        writer.opener.open = denied
        with self.assertRaisesRegex(backup.BackupFailure, "^GITHUB_WRITE_HTTP_403$"):
            writer.create_backup(SHA)

    def test_workflow_write_permissions_and_approval_are_manual_only(self):
        workflow = (Path(__file__).resolve().parents[2] / ".github/workflows/preserve-jarvis-developer-backup.yml").read_text()
        before, after = workflow.split("  preserve-approved-backup:")
        self.assertNotIn("contents: write", before)
        self.assertEqual(workflow.count("contents: write"), 1)
        self.assertIn("github.event_name == 'workflow_dispatch'", after)
        self.assertIn("inputs.approve_backup == true", after)
        self.assertIn("type: boolean", before)
        self.assertIn("APPROVED_SOURCE_SHA: ${{ inputs.approved_source_sha }}", after)
        self.assertIn("APPROVED_APK_SHA256: ${{ inputs.approved_apk_sha256 }}", after)
        self.assertNotIn("secrets.", workflow)
        self.assertNotIn("--clobber", workflow)
        self.assertNotIn("workflow_dispatch", before.split("  audit:", 1)[1])


if __name__ == "__main__":
    unittest.main()
