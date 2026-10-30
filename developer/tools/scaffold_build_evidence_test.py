"""Offline adversarial and result-parser contracts; never build or install apps."""

import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import scaffold_build_evidence as evidence


SHA = "1234567890abcdef1234567890abcdef12345678"


class ScaffoldEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        records = []
        for platform in evidence.PLATFORMS:
            project = self.root / platform
            project.mkdir()
            data = ("fixed " + platform + " source\n").encode()
            (project / "source.txt").write_bytes(data)
            with zipfile.ZipFile(self.root / (platform + ".zip"), "w") as archive:
                archive.writestr("source.txt", data)
            records.append(dict(platform=platform, fingerprint="a" * 64,
                                zipPath=platform + ".zip",
                                zipSha256=evidence.digest((self.root / (platform + ".zip")).read_bytes()),
                                files=[dict(path="source.txt", sha256=evidence.digest(data), bytes=len(data))]))
        self.manifest = dict(schemaVersion=1, sourceCommitSha=SHA,
                             templateVersion=evidence.TEMPLATE_VERSION, platforms=records)
        self.save_manifest()

    def save_manifest(self):
        (self.root / "evidence.json").write_text(json.dumps(self.manifest))

    def verify(self, platform="android", **kwargs):
        return evidence.verify_sources(self.root, SHA, platform, **kwargs)

    def test_all_three_exact_source_archives_verify(self):
        for platform in evidence.PLATFORMS:
            with self.subTest(platform=platform):
                self.assertEqual(SHA, self.verify(platform)["sourceCommitSha"])

    def checklist_manifest(self):
        self.manifest["templateVersion"] = evidence.FIXTURES["checklist"][1]
        self.manifest["platforms"] = self.manifest["platforms"][:1]
        self.save_manifest()

    def test_checklist_requires_explicit_fixture_and_one_android_record(self):
        self.checklist_manifest()
        self.assertEqual("jarvis-checklist-v1", self.verify(fixture="checklist")["templateVersion"])
        with self.assertRaisesRegex(ValueError, "template"):
            self.verify()
        with self.assertRaisesRegex(ValueError, "Unsupported platform"):
            self.verify("backend", fixture="checklist")
        with self.assertRaisesRegex(ValueError, "Unsupported fixture"):
            self.verify(fixture="untrusted")

    def test_starter_cannot_be_relabelled_as_checklist_by_call_site(self):
        with self.assertRaisesRegex(ValueError, "template"):
            self.verify(fixture="checklist")
        self.manifest["templateVersion"] = "jarvis-checklist-v1"
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "record count"):
            self.verify(fixture="checklist")

    def test_checklist_retains_revision_and_postbuild_source_checks(self):
        self.checklist_manifest()
        with self.assertRaisesRegex(ValueError, "SHA mismatch"):
            evidence.verify_sources(self.root, "f" * 40, "android", fixture="checklist")
        (self.root / "android/source.txt").write_bytes(b"replaced source")
        with self.assertRaisesRegex(ValueError, "byte count|digest"):
            self.verify(strict_tree=False, fixture="checklist")

    def test_apk_identity_is_fixed_by_fixture_and_wrong_package_is_rejected(self):
        path = self.root / "android/app/build/outputs/apk/debug/app-debug.apk"
        path.parent.mkdir(parents=True)
        path.write_bytes(b"test-only-placeholder")
        with patch.dict(os.environ, {"ANDROID_HOME": str(self.root / "test-sdk")}), \
                patch.object(evidence, "checked_output", return_value="package: name='com.jarvis.developer'\n"):
            with self.assertRaisesRegex(ValueError, "Unexpected preview APK package"):
                evidence.apk_record(self.root / "android", evidence.FIXTURES["checklist"][2])

    def test_preinstall_rechecks_both_artifact_identities_and_unit_results(self):
        self.checklist_manifest()
        source = self.verify(fixture="checklist")
        unit = {"totals": {"tests": 5, "failures": 0, "errors": 0, "skipped": 0}}
        app = dict(packageName="io.example.offlinechecklist", apkSha256="a" * 64, signerSha256="c" * 64)
        tests = dict(packageName="io.example.offlinechecklist.test", apkSha256="b" * 64, signerSha256="c" * 64)
        record = dict(source, schemaVersion=1, installed=False, published=False, unitTests=unit,
                      apk=app, deviceTestApk=tests)
        path = self.root / "android-build-evidence.json"
        path.write_text(json.dumps(record))
        with patch.object(evidence, "junit_records", return_value=unit), \
                patch.object(evidence, "apk_record", side_effect=[app, tests]):
            self.assertEqual(record, evidence.verify_checklist_build(self.root, SHA))
        for field, replacement in (("sourceCommitSha", "f" * 40), ("installed", True),
                                   ("published", True), ("deviceTestApk", app), ("unitTests", {})):
            path.write_text(json.dumps(dict(record, **{field: replacement})))
            with self.subTest(field=field), patch.object(evidence, "junit_records", return_value=unit), \
                    patch.object(evidence, "apk_record", side_effect=[app, tests]), self.assertRaises(ValueError):
                evidence.verify_checklist_build(self.root, SHA)

    def test_preinstall_rejects_different_app_and_instrumentation_signers(self):
        self.checklist_manifest()
        source = self.verify(fixture="checklist")
        app = dict(signerSha256="a" * 64)
        tests = dict(signerSha256="b" * 64)
        (self.root / "android-build-evidence.json").write_text(json.dumps(dict(
            source, schemaVersion=1, installed=False, published=False, unitTests={}, apk=app, deviceTestApk=tests)))
        with patch.object(evidence, "junit_records", return_value={}), \
                patch.object(evidence, "apk_record", side_effect=[app, tests]), \
                self.assertRaisesRegex(ValueError, "signer mismatch"):
            evidence.verify_checklist_build(self.root, SHA)

    def test_stale_revision_is_rejected(self):
        self.manifest["sourceCommitSha"] = "f" * 40
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "SHA mismatch"):
            self.verify()

    def test_branch_name_is_not_an_immutable_revision(self):
        with self.assertRaisesRegex(ValueError, "Immutable"):
            evidence.verify_sources(self.root, "feature/example", "android")

    def test_tampered_source_is_rejected(self):
        (self.root / "android/source.txt").write_text("tampered")
        with self.assertRaisesRegex(ValueError, "byte count|digest"):
            self.verify()

    def test_unlisted_source_is_rejected_before_build(self):
        (self.root / "android/extra.gradle").write_text("unexpected")
        with self.assertRaisesRegex(ValueError, "Unexpected"):
            self.verify()

    def test_build_outputs_are_permitted_only_after_prebuild_verification(self):
        (self.root / "android/build-output.bin").write_bytes(b"compiled")
        self.assertEqual("android", self.verify(strict_tree=False)["platform"])

    def test_source_cannot_change_during_build(self):
        (self.root / "android/source.txt").write_text("changed")
        with self.assertRaises(ValueError):
            self.verify(strict_tree=False)

    def test_parent_absolute_backslash_and_empty_components_rejected(self):
        for value in ("../secret", "/etc/passwd", "dir\\file", "dir//file", "./file"):
            with self.subTest(path=value), self.assertRaises(ValueError):
                evidence.relative_path(value)

    def test_symlink_source_rejected(self):
        source = self.root / "android/source.txt"
        data = source.read_bytes()
        source.unlink()
        outside = self.root / "outside.txt"
        outside.write_bytes(data)
        source.symlink_to(outside)
        with self.assertRaisesRegex(ValueError, "symlink"):
            self.verify()

    def test_duplicate_platform_identity_rejected(self):
        self.manifest["platforms"][2]["platform"] = "android"
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "identity"):
            self.verify()

    def test_duplicate_source_path_rejected(self):
        files = self.manifest["platforms"][0]["files"]
        files.append(dict(files[0]))
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            self.verify()

    def test_archive_tampering_rejected(self):
        with (self.root / "android.zip").open("ab") as archive:
            archive.write(b"tampered")
        with self.assertRaisesRegex(ValueError, "Archive digest"):
            self.verify()

    def test_self_consistent_archive_with_different_source_is_rejected(self):
        with zipfile.ZipFile(self.root / "android.zip", "w") as archive:
            archive.writestr("source.txt", b"wrong archive bytes")
        self.manifest["platforms"][0]["zipSha256"] = evidence.digest((self.root / "android.zip").read_bytes())
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "Archive.*(?:size|bytes)"):
            self.verify()

    def test_backend_requires_exact_successful_nonempty_run(self):
        path = self.root / "backend-tests.log"
        path.write_text("test_health ... ok\n\nRan 3 tests in 0.040s\n\nOK\n")
        self.assertEqual(3, evidence.backend_tests(path)["tests"])
        for text in ("Ran 0 tests in 0.001s\nOK\n", "Ran 3 tests in 0.001s\nFAILED (failures=1)\n",
                     "Ran 3 tests in 0.001s\nOK (skipped=1)\n", "Ran 1 test in 0.1s\nOK\nRan 1 test in 0.1s\nOK\n"):
            path.write_text(text)
            with self.subTest(output=text), self.assertRaises(ValueError):
                evidence.backend_tests(path)

    def test_junit_cross_checks_counts_and_rejects_skips(self):
        directory = self.root / "junit"
        directory.mkdir()
        path = directory / "TEST-preview.xml"
        template = '<testsuite tests="{}" failures="0" errors="0" skipped="{}"><testcase classname="Preview" name="counter"/></testsuite>'
        path.write_text(template.format(1, 0))
        self.assertEqual(1, evidence.junit_records(directory)["totals"]["tests"])
        for tests, skips in ((2, 0), (1, 1), (0, 0)):
            path.write_text(template.format(tests, skips))
            with self.subTest(tests=tests, skips=skips), self.assertRaises(ValueError):
                evidence.junit_records(directory)
        path.write_text(template.format(1, 0).replace('/></testsuite>', '><failure/></testcase></testsuite>'))
        with self.assertRaisesRegex(ValueError, "outcome count"):
            evidence.junit_records(directory)

    def test_windows_requires_one_successful_observed_smoke_and_binaries(self):
        project = self.root / "windows"
        binary = project / "app/bin/Release/net8.0-windows"
        binary.mkdir(parents=True)
        for name in ("StarterApp.exe", "StarterApp.dll"):
            (binary / name).write_bytes(b"compiled-preview")
        log = self.root / "windows-smoke.log"
        complete = dict(passed=True, checks=6, apartmentState="STA", titleMatches=True,
                        specificationMatches=True, initialStateMatches=True,
                        firstActionMatches=True, secondActionMatches=True)
        log.write_bytes(('JARVIS_PHASE6_WINDOWS_SMOKE=' + json.dumps(complete) + '\r\n').encode())
        result = evidence.windows_smoke(project, log)
        self.assertEqual(2, len(result["binaries"]))
        for text in ('JARVIS_PHASE6_WINDOWS_SMOKE={"passed":false}\n', 'no smoke\n',
                     'JARVIS_PHASE6_WINDOWS_SMOKE={"passed":true}\n',
                     'JARVIS_PHASE6_WINDOWS_SMOKE={"passed":true}\n' * 2):
            log.write_text(text)
            with self.subTest(log=text), self.assertRaises(ValueError):
                evidence.windows_smoke(project, log)

    def test_verify_command_cannot_claim_build_install_or_publication(self):
        with patch.dict(os.environ, GITHUB_SHA=SHA), patch("sys.stdout", new_callable=io.StringIO) as output:
            evidence.main(["verify", "--root", str(self.root), "--platform", "backend"])
        line = output.getvalue()
        self.assertTrue(line.startswith("JARVIS_PHASE6_BACKEND_SOURCES_VERIFIED="))
        result = json.loads(line.partition("=")[2])
        self.assertFalse(result["installed"])
        self.assertFalse(result["published"])
        self.assertNotIn("unitTests", result)
        self.assertFalse((self.root / "backend-build-evidence.json").exists())


if __name__ == "__main__":
    unittest.main()
