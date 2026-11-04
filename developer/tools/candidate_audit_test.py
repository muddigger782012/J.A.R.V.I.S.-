"""Modeled byte/identity protocol checks; real SDK signing is a separate CI job."""
import hashlib
import io
import os
from pathlib import Path
import tempfile
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import urllib.error
import zipfile

import candidate_audit as audit


SHA = "a" * 40
OTHER = "b" * 40
CERT = "c" * 64


def apk_fixture(payload=b"fixed modeled APK bytes"):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        # Byte-identity assertions must not depend on crossing ZIP's two-second wall clock boundary.
        archive.writestr(zipfile.ZipInfo("AndroidManifest.xml", date_time=(2024, 1, 1, 0, 0, 0)), payload)
    return output.getvalue()


def artifact_fixture(extra=None, kind="candidate"):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        for name in sorted(audit.INPUT_NAMES[kind]):
            archive.writestr(name, b"{}" if name == "tests.json" else apk_fixture())
        if extra:
            archive.writestr(extra, b"unexpected")
    return output.getvalue()


def facts(source=SHA, version=53, digest="d" * 64, certificate=CERT):
    return dict(sourceCommitSha=source, packageName=audit.PACKAGE, versionCode=version,
                apkSha256=digest, apkBytes=100, signerSha256=certificate)


class CandidateAuditTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.addCleanup(self.directory.cleanup)
        self.environment = patch.dict(os.environ, {"ANDROID_HOME": "/fixed-sdk"})
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def test_actual_fixture_bytes_are_hashed_and_inspected_using_explicit_modeled_sdk(self):
        data = apk_fixture()
        path = self.root / "fixture.apk"
        path.write_bytes(data)
        calls = []

        def modeled_tool(args):
            calls.append(args)
            if args[0].endswith("aapt"):
                return "package: name='com.jarvis.developer' versionCode='53' versionName='modeled'\n"
            return "Signer #1 certificate SHA-256 digest: " + CERT.upper() + "\n"

        result = audit.inspect_apk(path, SHA, command=modeled_tool)
        self.assertEqual(result["apkSha256"], hashlib.sha256(data).hexdigest())
        self.assertEqual(result["apkBytes"], len(data))
        self.assertEqual(result["signerSha256"], CERT)
        self.assertEqual(result["sourceCommitSha"], SHA)
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[1][1:4], ["verify", "--verbose", "--print-certs"])

    def test_wrong_package_and_multiple_signers_rejected(self):
        path = self.root / "fixture.apk"
        path.write_bytes(apk_fixture())
        with self.assertRaises(audit.AuditFailure):
            audit.inspect_apk(path, SHA, command=lambda args: "package: name='com.jarvis.beta' versionCode='53'")

        def two_signers(args):
            if args[0].endswith("aapt"):
                return "package: name='com.jarvis.developer' versionCode='53'"
            return "Signer #1 certificate SHA-256 digest: " + CERT + "\nSigner #2 certificate SHA-256 digest: " + "e" * 64

        with self.assertRaises(audit.AuditFailure):
            audit.inspect_apk(path, SHA, command=two_signers)

    def test_apk_symlink_and_traversal_entries_rejected(self):
        path = self.root / "fixture.apk"
        path.write_bytes(apk_fixture())
        link = self.root / "link.apk"
        link.symlink_to(path)
        with self.assertRaises(audit.AuditFailure):
            audit.apk_bytes(link)
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("../outside", b"payload")
        with self.assertRaises(audit.AuditFailure):
            audit.apk_bytes(path)

    def test_fixed_artifact_entries_round_trip(self):
        audit.extract_inputs(artifact_fixture(), self.root, "candidate")
        self.assertEqual({path.name for path in self.root.iterdir()}, audit.INPUT_NAMES["candidate"])
        self.assertEqual((self.root / "tests.json").read_bytes(), b"{}")

    def test_extra_traversal_duplicate_and_symlink_artifact_entries_rejected(self):
        for name in ("../outside", "tools/apksigner", "candidate-unsigned.apk"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(audit.AuditFailure):
                    audit.extract_inputs(artifact_fixture(name), directory, "candidate")
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as archive:
            for name in audit.INPUT_NAMES["candidate"]:
                item = zipfile.ZipInfo(name)
                item.external_attr = 0o120777 << 16
                archive.writestr(item, b"target")
        with self.assertRaises(audit.AuditFailure):
            audit.extract_inputs(output.getvalue(), self.root, "candidate")

    def test_candidate_and_recovery_artifacts_have_disjoint_fixed_members(self):
        audit.extract_inputs(artifact_fixture(kind="recovery"), self.root, "recovery")
        self.assertEqual({path.name for path in self.root.iterdir()}, {"recovery-unsigned.apk"})
        with self.assertRaises(audit.AuditFailure):
            audit.extract_inputs(artifact_fixture(), self.root, "recovery")
        with self.assertRaises(audit.AuditFailure):
            audit.extract_inputs(artifact_fixture(kind="recovery"), self.root, "candidate")

    def artifact_protocol(self):
        candidate = artifact_fixture()
        recovery = artifact_fixture(kind="recovery")
        environment = {"GITHUB_SHA": SHA, "GITHUB_RUN_ID": "12", "GITHUB_RUN_ATTEMPT": "2",
                       "CANDIDATE_ARTIFACT_ID": "100", "RECOVERY_ARTIFACT_ID": "101",
                       "CANDIDATE_ARTIFACT_DIGEST": hashlib.sha256(candidate).hexdigest(),
                       "RECOVERY_ARTIFACT_DIGEST": hashlib.sha256(recovery).hexdigest()}
        values = {f"/repos/{audit.REPO}/actions/runs/12/attempts/2":
                  dict(id=12, run_attempt=2, head_sha=SHA, head_branch=audit.BRANCH,
                       path=".github/workflows/evaluate-jarvis-developer.yml")}
        for kind, number, data in (("candidate", 100, candidate), ("recovery", 101, recovery)):
            prefix = f"/repos/{audit.REPO}/actions/artifacts/{number}"
            values[prefix] = dict(id=number, name=audit.ARTIFACT_NAMES[kind] + "-attempt-2", expired=False,
                                  size_in_bytes=len(data), digest="sha256:" + hashlib.sha256(data).hexdigest(),
                                  workflow_run=dict(id=12, head_sha=SHA, head_branch=audit.BRANCH))
            values[prefix + "/zip"] = data
        return environment, values

    def test_only_exact_needs_artifact_ids_are_downloaded_and_verified(self):
        environment, values = self.artifact_protocol()
        calls = []

        def get(path, **kwargs):
            calls.append(path)
            self.assertNotIn("?", path)
            return values[path]

        with patch.dict(os.environ, environment):
            audit.download_inputs(SimpleNamespace(get=get), self.root)
        self.assertEqual(len(calls), 5)
        self.assertEqual({path.name for path in self.root.iterdir()}, audit.INPUT_NAMES["candidate"] | audit.INPUT_NAMES["recovery"])

    def test_real_http_adapter_handles_exact_metadata_and_unauthenticated_storage_redirect(self):
        environment, values = self.artifact_protocol()
        requests = []
        payloads = {}

        class Response(io.BytesIO):
            status = 200

        def open_request(request, timeout):
            requests.append(request)
            if request.full_url.startswith("https://api.github.com"):
                path = request.full_url.removeprefix("https://api.github.com")
                value = values[path]
                if isinstance(value, bytes):
                    location = "https://productionresultssa1.blob.core.windows.net/fixed/" + str(len(payloads)) + "?signed=hidden"
                    payloads[location] = value
                    raise urllib.error.HTTPError(request.full_url, 302, "remote-text-hidden", {"Location": location}, None)
                return Response(audit.encoded(value))
            self.assertIsNone(request.get_header("Authorization"))
            return Response(payloads[request.full_url])

        api = audit.GitHubRead("modeled-token-must-stay-at-api")
        api.opener = SimpleNamespace(open=open_request)
        audit.download_inputs(api, self.root, environment)
        self.assertEqual(len(requests), 7)
        self.assertEqual(api.requests, 5)
        self.assertTrue(all(request.get_header("Authorization") == "Bearer modeled-token-must-stay-at-api"
                            for request in requests if request.full_url.startswith("https://api.github.com")))

    def test_http_errors_report_only_fixed_text_and_numeric_status(self):
        api = audit.GitHubRead("modeled-token")

        def forbidden(request, timeout):
            raise urllib.error.HTTPError(request.full_url + "?sensitive=hidden", 403, "secret remote body", {}, None)

        api.opener = SimpleNamespace(open=forbidden)
        with self.assertRaises(audit.AuditFailure) as caught:
            api.get(f"/repos/{audit.REPO}/actions/artifacts/100")
        self.assertEqual(str(caught.exception), "GitHub read failed HTTP 403")

    def test_binary_rest_accept_media_types_distinguish_actions_zip_from_release_asset(self):
        api = audit.GitHubRead("modeled-token")
        paths = ((f"/repos/{audit.REPO}/actions/artifacts/100/zip", "application/vnd.github+json"),
                 (f"/repos/{audit.REPO}/releases/assets/200", "application/octet-stream"))

        class Response(io.BytesIO):
            status = 200

        for path, expected in paths:
            calls = []

            def open_request(request, timeout):
                calls.append(request)
                if request.get_header("Accept") != expected:
                    raise urllib.error.HTTPError(request.full_url, 415, "unsupported remote media body", {}, None)
                return Response(b"modeled-raw-binary-content")

            api.opener = SimpleNamespace(open=open_request)
            with self.subTest(path=path):
                self.assertEqual(api.get(path, binary=True), b"modeled-raw-binary-content")
                self.assertEqual(calls[0].get_header("Accept"), expected)
        with self.assertRaises(audit.AuditFailure):
            api.get(f"/repos/{audit.REPO}/actions/runs/12", binary=True)

    def test_probe_uses_explicit_old_identity_without_overwriting_current_controller(self):
        identity, _ = self.artifact_protocol()
        args = SimpleNamespace(directory=self.root / "probe", run_id=12, run_attempt=2, source_sha=SHA,
                               candidate_artifact_id="100", candidate_artifact_digest=identity["CANDIDATE_ARTIFACT_DIGEST"],
                               recovery_artifact_id="101", recovery_artifact_digest=identity["RECOVERY_ARTIFACT_DIGEST"])
        with patch.dict(os.environ, {"GITHUB_SHA": OTHER, "GITHUB_RUN_ID": "99", "GH_TOKEN": "modeled-token"}), patch.object(audit, "download_inputs") as download:
            audit.probe_unsigned(args)
            self.assertEqual(os.environ["GITHUB_SHA"], OTHER)
            self.assertEqual(os.environ["GITHUB_RUN_ID"], "99")
            self.assertEqual(download.call_args.args[2], identity)

    def test_stale_attempt_wrong_source_name_id_digest_expiry_and_corrupt_bytes_rejected(self):
        for field, value in (("id", 102), ("name", "Jarvis-Developer-unsigned-candidate-attempt-1"),
                             ("expired", True), ("digest", None), ("size_in_bytes", audit.MAX_ZIP + 1),
                             ("workflow_run", dict(id=12, head_sha=OTHER, head_branch=audit.BRANCH))):
            environment, values = self.artifact_protocol()
            values[f"/repos/{audit.REPO}/actions/artifacts/100"][field] = value
            with self.subTest(field=field), patch.dict(os.environ, environment):
                with self.assertRaises(audit.AuditFailure):
                    audit.download_inputs(SimpleNamespace(get=lambda path, **kwargs: values[path]), self.root)
        for field, value in (("run_attempt", 1), ("head_sha", OTHER), ("head_branch", "main"), ("path", "other.yml")):
            environment, values = self.artifact_protocol()
            values[f"/repos/{audit.REPO}/actions/runs/12/attempts/2"][field] = value
            with self.subTest(run_field=field), patch.dict(os.environ, environment):
                with self.assertRaises(audit.AuditFailure):
                    audit.download_inputs(SimpleNamespace(get=lambda path, **kwargs: values[path]), self.root)
        environment, values = self.artifact_protocol()
        values[f"/repos/{audit.REPO}/actions/artifacts/100/zip"] += b"corrupted"
        with patch.dict(os.environ, environment), self.assertRaises(audit.AuditFailure):
            audit.download_inputs(SimpleNamespace(get=lambda path, **kwargs: values[path]), self.root)

    def test_missing_or_reused_needs_artifact_outputs_rejected(self):
        for field, value in (("CANDIDATE_ARTIFACT_ID", ""), ("CANDIDATE_ARTIFACT_ID", "../100"),
                             ("CANDIDATE_ARTIFACT_DIGEST", ""), ("RECOVERY_ARTIFACT_ID", "100")):
            environment, values = self.artifact_protocol()
            environment[field] = value
            with self.subTest(field=field, value=value), tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, environment):
                with self.assertRaises(audit.AuditFailure):
                    audit.download_inputs(SimpleNamespace(get=lambda path, **kwargs: values[path]), directory)

    def test_recovery_collection_only_reads_its_own_checkout_and_apk(self):
        checkout = self.root / "recovery"
        apk = checkout / "developer/build/outputs/apk/release/developer-release-unsigned.apk"
        apk.parent.mkdir(parents=True)
        apk.write_bytes(apk_fixture())
        output = self.root / "collected"
        calls = []

        def modeled_git(args, directory):
            calls.append((args, directory))
            return SHA if args[0] == "rev-parse" else ""

        with patch.dict(os.environ, {"RECOVERY_SHA": SHA}), patch.object(audit, "git", modeled_git):
            audit.collect(SimpleNamespace(checkout=checkout, directory=output), "recovery")
        self.assertEqual({path.name for path in output.iterdir()}, {"recovery-unsigned.apk"})
        self.assertTrue(all(directory == checkout for _, directory in calls))
        self.assertEqual((output / "recovery-unsigned.apk").read_bytes(), apk_fixture())

    def test_fixed_command_stream_budget_and_timeout_fail_closed(self):
        self.assertEqual(audit.fixed_command([sys.executable, "-c", "print('bounded')"]), "bounded\n")
        with self.assertRaises(audit.AuditFailure):
            audit.fixed_command([sys.executable, "-c", "import sys; sys.stderr.write('x'*260000)"], timeout=5)
        with self.assertRaises(audit.AuditFailure):
            audit.fixed_command([sys.executable, "-c", "import time; time.sleep(2)"], timeout=0.05)

    def test_duplicate_json_keys_and_over_budget_data_rejected(self):
        for data in (b'{"verified":false,"verified":true}', b" " * 128_001):
            with self.assertRaises(audit.AuditFailure):
                audit.decode_json(data)

    def ready_evidence(self):
        baseline = facts()
        candidate = facts(source=OTHER, version=54, digest="e" * 64)
        candidate.pop("sourceCommitSha")
        candidate.update(commitSha=OTHER, branch="feature/jarvis-developer-task/fixture-123456789abc",
                         controllerCommitSha=SHA, controllerBranch=audit.BRANCH, runId=12, runAttempt=1)
        backup = dict(**baseline, verified=True)
        rebuild = dict(**facts(version=55, digest="f" * 64), verified=True)
        tests = dict(passed=True, tests=220, failures=0, errors=0, skipped=0, lintErrors=0, lintWarnings=185)
        source = dict(baseCommitSha=SHA, changedPaths=["developer/src/main/java/com/jarvis/developer/TaskFileSummary.kt"],
                      scopeVerified=True, controlsUnchanged=True, branchIsolated=True)
        return [candidate, baseline, backup, rebuild, tests, source]

    def test_complete_exact_backup_and_higher_recovery_can_be_ready(self):
        result = audit.evaluate(*self.ready_evidence())
        self.assertEqual(result["status"], "READY")
        self.assertEqual(result["recovery"]["missingReasons"], [])

    def test_missing_backup_never_passes_even_with_signed_rebuild(self):
        values = self.ready_evidence()
        values[2] = None
        result = audit.evaluate(*values)
        self.assertEqual(result["status"], "BLOCKED")
        self.assertEqual(result["recovery"]["missingReasons"], ["BACKUP_MISSING"])
        self.assertTrue(result["recovery"]["rebuild"]["verified"])

    def test_byte_source_size_version_package_and_signer_backup_mismatch_block(self):
        for key, value in (("sourceCommitSha", OTHER), ("apkSha256", "1" * 64), ("apkBytes", 99),
                           ("versionCode", 52), ("packageName", "com.jarvis.beta"), ("signerSha256", "2" * 64)):
            with self.subTest(key=key):
                values = self.ready_evidence()
                values[2][key] = value
                self.assertIn("BACKUP_UNVERIFIED", audit.evaluate(*values)["recovery"]["missingReasons"])

    def test_recovery_must_rebuild_exact_known_good_source_with_same_key_and_newer_version(self):
        for key, value in (("sourceCommitSha", OTHER), ("signerSha256", "2" * 64), ("versionCode", 54),
                           ("packageName", "com.jarvis.beta"), ("verified", False)):
            with self.subTest(key=key):
                values = self.ready_evidence()
                values[3][key] = value
                self.assertIn("RECOVERY_REBUILD_UNVERIFIED", audit.evaluate(*values)["recovery"]["missingReasons"])

    def test_candidate_wrong_signer_or_non_increasing_version_block(self):
        for key, value in (("signerSha256", "2" * 64), ("versionCode", 53)):
            values = self.ready_evidence()
            values[0][key] = value
            self.assertIn("CANDIDATE_UNVERIFIED", audit.evaluate(*values)["recovery"]["missingReasons"])

    def test_bootstrap_is_not_an_isolated_promotable_candidate(self):
        values = self.ready_evidence()
        values[5]["branchIsolated"] = False
        self.assertIn("CANDIDATE_UNVERIFIED", audit.evaluate(*values)["recovery"]["missingReasons"])

    def test_failed_skipped_or_incomplete_tests_block(self):
        for key, value in (("passed", False), ("tests", 0), ("failures", 1), ("errors", 1), ("skipped", 1), ("lintErrors", 1)):
            with self.subTest(key=key):
                values = self.ready_evidence()
                values[4][key] = value
                self.assertIn("TESTS_INCOMPLETE", audit.evaluate(*values)["recovery"]["missingReasons"])

    def test_protected_controls_and_existing_tests_cannot_be_candidate_changes(self):
        environment = {"GITHUB_SHA": SHA, "GITHUB_REF": "refs/heads/" + audit.BRANCH}
        branch = "feature/jarvis-developer-task/fixture-123456789abc"
        for changed in ("M\tdeveloper/build.gradle", "M\t.github/workflows/build.yml", "M\tdeveloper/tools/candidate_audit.py",
                        "M\tdeveloper/src/main/java/com/jarvis/developer/PatchEngine.kt",
                        "M\tdeveloper/src/main/java/com/jarvis/developer/PatchPreviewCancellation.kt",
                        "M\tdeveloper/src/main/java/com/jarvis/developer/PersonalRepair.kt",
                        "M\tdeveloper/src/main/java/com/jarvis/developer/PersonalRepairWorkbench.kt",
                        "M\tdeveloper/src/main/java/com/jarvis/developer/PrivateCheckpointFile.kt",
                        "M\tdeveloper/src/main/java/com/jarvis/developer/RepairHistoryStore.kt",
                        "M\tdeveloper/src/main/java/com/jarvis/developer/RepairHistoryWorkbench.kt",
                        "M\tdeveloper/src/test/java/com/jarvis/developer/TaskFileSummaryTest.kt"):
            def modeled_git(args, controller):
                if args[:2] == ["rev-parse", "HEAD"]:
                    return SHA
                if args[0] == "rev-parse":
                    return OTHER
                if args[0] == "diff":
                    return changed
                return ""
            with self.subTest(path=changed), patch.dict(os.environ, environment), patch.object(audit, "git", modeled_git):
                with self.assertRaises(audit.AuditFailure):
                    audit.validate_source("modeled-controller", OTHER, branch, "0" * 40)

    def test_recognized_credential_branch_rejected_before_git_or_outputs(self):
        branch = "feature/jarvis-developer-task/ghp_ABCDEFGHIJKLMNOPQRSTUVWX-123456789abc"
        with patch.object(audit, "git") as git:
            with self.assertRaises(audit.AuditFailure):
                audit.validate_source("modeled-controller", OTHER, branch, SHA)
            git.assert_not_called()

    def test_candidate_content_rejects_process_reflection_credentials_and_wrong_package(self):
        path = "developer/src/main/java/com/jarvis/developer/TaskFileSummary.kt"
        for content in ("package com.jarvis.developer\n ProcessBuilder()", "package com.jarvis.developer\n javaClass",
                        'package com.jarvis.developer\n val token = "literal-secret"',
                        "package com.jarvis.beta\n class Summary"):
            with self.subTest(content=content), self.assertRaises(audit.AuditFailure):
                audit.validate_content(path, content)
        audit.validate_content(path, "package com.jarvis.developer\n class Summary")

    def test_workflow_has_separate_fresh_builds_exact_outputs_and_trusted_signing_job(self):
        workflow = Path(__file__).resolve().parents[2] / ".github/workflows/evaluate-jarvis-developer.yml"
        text = workflow.read_text()
        candidate = text.split("  candidate-build:\n", 1)[1].split("  recovery-build:\n", 1)[0]
        recovery = text.split("  recovery-build:\n", 1)[1].split("  candidate-evaluation:\n", 1)[0]
        signing = text.split("  candidate-evaluation:\n", 1)[1]
        self.assertNotIn("path: recovery", candidate)
        self.assertNotIn("path: candidate", recovery)
        self.assertIn("candidate-gradle", candidate)
        self.assertIn("recovery-gradle", recovery)
        for build in (candidate, recovery):
            self.assertIn("runs-on: ubuntu-latest", build)
            self.assertNotIn("secrets.", build)
            self.assertNotIn("actions/cache", build)
            self.assertIn("steps.upload.outputs.artifact-id", build)
            self.assertIn("--init-script ../controller/developer/tools/unsigned.init.gradle", build)
        self.assertNotIn("path: candidate", signing)
        self.assertNotIn("path: recovery", signing)
        self.assertNotIn("bash ./gradlew", signing)
        self.assertIn("needs.candidate-build.outputs.artifact_id", signing)
        self.assertIn("needs.recovery-build.outputs.artifact_id", signing)
        self.assertNotIn("contents: write", text)
        self.assertNotIn("gh release", text)

    def test_fixed_signing_cli_uses_password_environment_and_never_gradle(self):
        calls = []
        audit.sign_apk(self.root / "unsigned.apk", self.root / "signed.apk", self.root / "key.jks", "fixed-alias", command=lambda args: calls.append(args))
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[0][1:4], ["-f", "4", str(self.root / "unsigned.apk")])
        self.assertIn("env:STORE_PASS", calls[1])
        self.assertIn("env:KEY_PASS", calls[1])
        self.assertFalse(any("gradle" in part for call in calls for part in call))


if __name__ == "__main__":
    unittest.main()
