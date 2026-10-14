"""Regressions for channel separation, signed upgrade gates and publication safety."""
from dataclasses import replace
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import stage_update_channels as stage
import publish_update_channels as publish
import verify_beta_configuration as configuration
import verify_legacy_apk_budget as budget


def info(path=Path("fixture.apk"), channel="jarvis", abi="arm64-v8a", code=395):
    return stage.ApkInfo(path, stage.PACKAGES[channel], code, f"3.5.{code}", 26,
                         (abi,), stage.PERMANENT_SIGNER, 150_000, "a" * 64,
                         "Beta" if channel == "beta" else "J.A.R.V.I.S.")


class SignedChannelTests(unittest.TestCase):
    def test_channel_manifest_lists_exact_matching_distribution_and_integrity(self):
        for channel in ("jarvis", "beta"):
            manifest = stage.build_manifest(channel, "b" * 40,
                                             [info(channel=channel, abi=abi) for abi in stage.ABIS])
            self.assertEqual(manifest["applicationId"], stage.PACKAGES[channel])
            self.assertEqual(manifest["versionCode"], 395)
            for artifact in manifest["artifacts"]:
                self.assertIn(f"/releases/download/{channel}-latest/", artifact["url"])
                self.assertEqual(artifact["sizeBytes"], 150_000)
                self.assertEqual(artifact["signingCertificateSha256"], stage.PERMANENT_SIGNER)

    def test_cross_channel_wrong_signer_downgrade_and_abi_are_rejected(self):
        previous = info(code=394)
        bad = [info(channel="beta"), replace(info(), signer="f" * 64),
               info(code=394), replace(info(), abis=("mips",))]
        for candidate in bad:
            with self.subTest(candidate=candidate), self.assertRaises(ValueError):
                stage.validate_upgrade(candidate, previous, "jarvis", 395)

    def test_signed_tool_failure_is_never_treated_as_compatible(self):
        with patch.object(stage, "run_tool", side_effect=subprocess.CalledProcessError(1, "apksigner")):
            with self.assertRaises(subprocess.CalledProcessError):
                stage.inspect_apk(Path("fixture.apk"), "aapt", "apksigner")

    def test_bridge_is_newer_than_raw_cohort_and_full_beta_is_next(self):
        raw = info(channel="beta", code=391)
        bridge = replace(info(channel="beta", code=394), abis=stage.ABIS)
        full = replace(info(channel="beta", code=395), abis=stage.ABIS)
        stage.validate_upgrade(bridge, raw, "beta", 394)
        stage.validate_upgrade(full, bridge, "beta", 395)
        stage.validate_legacy_alias(bridge)
        with self.assertRaises(ValueError):
            stage.validate_legacy_alias(replace(bridge, size=stage.MAX_LEGACY_BYTES))
        with self.assertRaises(ValueError):
            stage.validate_legacy_alias(info(channel="beta"))

    def test_missing_or_mixed_splits_and_bad_source_are_rejected(self):
        splits = [info(abi=abi) for abi in stage.ABIS]
        for candidates, revision in [(splits[:-1], "b" * 40),
                                     ([splits[0], splits[0], splits[2]], "b" * 40),
                                     ([splits[0], replace(splits[1], version_code=396), splits[2]], "b" * 40),
                                     (splits, "main")]:
            with self.assertRaises(ValueError):
                stage.build_manifest("jarvis", revision, candidates)

    def test_previous_metadata_rejects_same_version_and_unknown_signer(self):
        manifest = stage.build_manifest("jarvis", "b" * 40, [info(abi=abi) for abi in stage.ABIS])
        with tempfile.TemporaryDirectory() as directory:
            previous = Path(directory) / "jarvis.json"
            previous.write_text(json.dumps(manifest))
            with self.assertRaises(ValueError):
                stage.validate_previous_metadata(manifest, previous)
            old = dict(manifest, versionCode=394,
                       artifacts=[dict(manifest["artifacts"][0], signingCertificateSha256="f" * 64)])
            previous.write_text(json.dumps(old))
            with self.assertRaises(ValueError):
                stage.validate_previous_metadata(manifest, previous)


class PublicationTests(unittest.TestCase):
    def test_staging_produces_separate_full_beta_metadata_and_raw_bridge(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            records = {}

            def outputs(name, channel, code):
                folder = root / name
                folder.mkdir()
                for abi in (*stage.ABIS, "universal"):
                    apk = folder / f"fixture-{abi}.apk"
                    apk.write_bytes(f"{name}-{abi}".encode() * 20_000)
                    records[apk] = replace(info(apk, channel, abi, code),
                                           abis=stage.ABIS if abi == "universal" else (abi,),
                                           size=apk.stat().st_size, sha256=stage.file_digest(apk))
                return folder

            original = outputs("original", "jarvis", 395)
            beta = outputs("full-beta", "beta", 395)
            bridge = outputs("bridge", "beta", 394)
            for name, channel, code in (("prior-main", "jarvis", 394),
                                         ("prior-full", "beta", 394), ("prior-raw", "beta", 391)):
                records[root / name] = info(root / name, channel, code=code)
            args = SimpleNamespace(output=root / "staged", aapt="aapt", apksigner="apksigner",
                                   original_dir=original, previous_original=root / "prior-main",
                                   beta_dir=beta, bridge_dir=bridge,
                                   previous_beta_full=root / "prior-full", previous_beta_raw=root / "prior-raw",
                                   previous_metadata_dir=root / "metadata", version_code=395,
                                   source_commit="b" * 40)
            with patch.object(stage, "inspect_apk", side_effect=lambda path, *unused: records[Path(path)]):
                stage.stage(args)
            receipt = publish.validate_stage(args.output, args.source_commit)
            self.assertEqual(receipt["channels"], ["jarvis", "beta"])
            beta_manifest = json.loads((args.output / "pointers/updates/beta.json").read_text())
            self.assertEqual(beta_manifest["versionCode"], 395)
            self.assertEqual((args.output / "pointers/artifacts/beta.apk").read_bytes(),
                             (bridge / "fixture-universal.apk").read_bytes())
            self.assertEqual((args.output / "releases/beta/Beta.apk").read_bytes(),
                             (beta / "fixture-universal.apk").read_bytes())
            (args.output / "releases/beta/Beta.apk").write_bytes(b"damaged")
            with self.assertRaises(ValueError):
                publish.validate_stage(args.output, args.source_commit)

    def test_uploaded_byte_mismatch_fails_before_pointer_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = root / "stage/releases/jarvis"
            release.mkdir(parents=True)
            (release / "Jarvis.apk").write_bytes(b"verified")

            def fake_command(*args, **kwargs):
                if args[:3] == ("gh", "release", "download"):
                    (root / "jarvis-latest/Jarvis.apk").write_bytes(b"wrong bytes")
                return ""

            with patch.object(publish, "command", side_effect=fake_command):
                with self.assertRaises(ValueError):
                    publish.upload_and_verify(root / "stage", "jarvis", "jarvis-latest", root)

    def test_upload_failure_never_commits_legacy_aliases_or_metadata(self):
        with patch.dict(os.environ, GITHUB_REF="refs/heads/main", GITHUB_REPOSITORY=stage.REPOSITORY), \
                patch.object(publish, "validate_stage", return_value={"channels": ["jarvis"], "versionCode": 395}), \
                patch.object(publish, "source_is_current", return_value=True), \
                patch.object(publish, "ensure_release"), \
                patch.object(publish, "upload_and_verify", side_effect=ValueError("upload failed")), \
                patch.object(publish, "command") as command:
            with self.assertRaises(ValueError):
                publish.publish(Path("anything"), "b" * 40)
            command.assert_not_called()

    def test_feature_branch_cannot_publish_even_valid_staged_files(self):
        with patch.dict(os.environ, GITHUB_REF="refs/heads/feature", GITHUB_REPOSITORY=stage.REPOSITORY), \
                patch.object(publish, "command") as command:
            with self.assertRaises(ValueError):
                publish.publish(Path("anything"), "b" * 40)
            command.assert_not_called()

    def test_superseded_source_never_uploads_or_moves_pointers(self):
        with patch.dict(os.environ, GITHUB_REF="refs/heads/main", GITHUB_REPOSITORY=stage.REPOSITORY), \
                patch.object(publish, "validate_stage", return_value={"channels": ["jarvis"]}), \
                patch.object(publish, "source_is_current", return_value=False), \
                patch.object(publish, "ensure_release") as release, \
                patch.object(publish, "command") as command:
            publish.publish(Path("anything"), "b" * 40)
            release.assert_not_called()
            command.assert_not_called()

    def test_only_pointer_changes_are_ignored_by_source_guard(self):
        for differences, expected in [("artifacts/app-debug.apk\nupdates/jarvis.json", True),
                                      ("app/build.gradle\nupdates/jarvis.json", False),
                                      ("AGENTS.md", False)]:
            with patch.object(publish, "command", side_effect=["", differences]):
                self.assertEqual(publish.source_is_current("b" * 40), expected)

    def test_tampered_or_missing_staged_files_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            asset = root / "fixture.apk"
            asset.write_bytes(b"verified")
            receipt = {"sourceCommit": "b" * 40, "channels": ["jarvis"],
                       "files": {"fixture.apk": stage.file_digest(asset)}}
            (root / "receipt.json").write_text(json.dumps(receipt))
            asset.write_bytes(b"tampered")
            with self.assertRaises(ValueError):
                publish.validate_stage(root, "b" * 40)
            asset.unlink()
            with self.assertRaises(ValueError):
                publish.validate_stage(root, "b" * 40)


class LegacyPackagingBudgetTests(unittest.TestCase):
    def fixtures(self, root):
        model = root / 'sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01'
        model.mkdir()
        for name in ['tokens.txt', 'bpe.model', 'jarvis-keywords.txt',
                     'encoder-fp32.onnx', 'decoder-fp32.onnx', 'joiner-fp32.onnx']:
            (model / name).write_bytes(('fixture-' + name).encode())
        runtime = root / 'runtime.aar'
        with zipfile.ZipFile(runtime, 'w') as archive:
            for abi in stage.ABIS:
                archive.writestr(f'jni/{abi}/libsherpa.so', b'native')
        return model, runtime

    def apk(self, root, model, name='universal.apk', missing_abi=None, changed_asset=None):
        path = root / name
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('classes.dex', b'fixture' * 20_000)
            for abi in stage.ABIS:
                if abi != missing_abi:
                    archive.writestr(f'lib/{abi}/libsherpa.so', b'native')
            for asset in model.iterdir():
                data = b'bad bytes' if asset.name == changed_asset else asset.read_bytes()
                archive.writestr(f'assets/{model.name}/{asset.name}', data)
        return path

    def test_real_zip_packaging_checks_production_assets_and_native_coverage(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model, runtime = self.fixtures(root)
            apk = self.apk(root, model)
            result = budget.verify(apk, model, runtime)
            self.assertEqual(result['sizeBytes'], apk.stat().st_size)
            self.assertEqual(result['verifiedKwsAssets'], 6)
            self.assertFalse(result['signedCompatibilityVerified'])
            self.assertEqual(result['signingHeadroomBytes'], 64_000)
            self.assertEqual(budget.find_universal(root), apk)
            for options in [dict(missing_abi='armeabi-v7a'), dict(changed_asset='encoder-fp32.onnx')]:
                bad = self.apk(root, model, name='bad.apk', **options)
                with self.assertRaises(ValueError):
                    budget.verify(bad, model, runtime)
                bad.unlink()

    def test_undersized_models_cannot_hide_legacy_size_regression(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model, runtime = self.fixtures(root)
            apk = self.apk(root, model)
            limit = apk.stat().st_size + budget.UNSIGNED_HEADROOM_BYTES
            with patch.object(budget, 'MAX_LEGACY_BYTES', limit):
                with self.assertRaises(ValueError):
                    budget.verify(apk, model, runtime)
            (model / 'encoder-fp32.onnx').unlink()
            with self.assertRaises(ValueError):
                budget.verify(apk, model, runtime)

    def test_extra_runtime_abi_is_rejected_with_packaging_diagnostics(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model, runtime = self.fixtures(root)
            apk = self.apk(root, model)
            with zipfile.ZipFile(apk, 'a') as archive:
                archive.writestr('lib/x86/libsherpa.so', b'native')
            with self.assertRaisesRegex(ValueError, 'universal.apk:.*x86'):
                budget.find_universal(root)
            with self.assertRaises(ValueError):
                budget.verify(apk, model, runtime)


class ConfigurationTests(unittest.TestCase):
    def test_beta_presence_and_main_isolation_without_exposing_values(self):
        sentinel = "not-an-api-key-for-tests"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / "BuildConfig.java"
            apk = root / "fixture.apk"
            config.write_text('public static final String BETA_GEMINI_API_KEY = ' + json.dumps(sentinel) + ';')
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", b"dex\x00" + sentinel.encode())
            configuration.verify(config, [apk], sentinel, True)
            with self.assertRaises(ValueError):
                configuration.verify(config, [apk], sentinel, False)
            with self.assertRaises(ValueError):
                configuration.verify(config, [apk], "", True)
            config.write_text('public static final String BETA_GEMINI_API_KEY = "";')
            with self.assertRaises(ValueError):
                configuration.verify(config, [apk], sentinel, False)
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", b"dex\x00main")
            configuration.verify(config, [apk], sentinel, False)


if __name__ == "__main__":
    unittest.main()
