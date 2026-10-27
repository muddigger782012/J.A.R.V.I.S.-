"""Offline host-driver contracts. These tests never invoke adb or an emulator."""

import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch
import xml.etree.ElementTree as ET

import device_validation as validation


SHA = "1234567890abcdef1234567890abcdef12345678"


def passing_case():
    def status(code):
        return "\n".join((
            "INSTRUMENTATION_STATUS: class=" + validation.TEST_CLASS,
            "INSTRUMENTATION_STATUS: current=1",
            "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner",
            "INSTRUMENTATION_STATUS: numtests=1",
            "INSTRUMENTATION_STATUS: test=" + validation.TEST_METHOD,
            "INSTRUMENTATION_STATUS_CODE: " + code,
        ))
    return status("1") + "\n" + status("0") + "\nINSTRUMENTATION_RESULT: stream=\nTime: 1.0\n\nOK (1 test)\n\nINSTRUMENTATION_CODE: -1\n"


def node(klass, text="", **attributes):
    return ET.Element("node", {"package": validation.PACKAGE,
                              "class": "android.widget." + klass,
                              "bounds": "[0,10][300,60]", "text": text,
                              "enabled": "true", **attributes})


def fresh_nodes():
    result = [node("ScrollView", bounds="[0,0][320,640]")]
    result.extend(node("EditText", hint) for hint in
                  (validation.PROVIDER_HINT, validation.TOKEN_HINT, validation.REVIEW_HINT))
    result.extend(node("Button", label, enabled="false") for label in validation.GATE_BUTTONS)
    result.extend(node("CheckBox", label, enabled="false", checked="false")
                  for label in validation.GATE_CHECKBOXES)
    result.append(node("TextView", "Version 16 is awaiting your health confirmation."))
    return result


class ConfigurationAdb:
    def __init__(self, physical=False, fail_instrument=False, restore_failure=False,
                 missing_package=None, missing_runner=False):
        self.commands = []
        self.deadline = 0
        self.physical = physical
        self.fail_instrument = fail_instrument
        self.restore_failure = restore_failure
        self.missing_package = missing_package
        self.missing_runner = missing_runner
        self.size = "720x1440"
        self.density = None
        self.settings = {"font_scale": "null", "accelerometer_rotation": "1", "user_rotation": "2"}
        self.night = "auto"

    def run(self, *args, **kwargs):
        self.commands.append(args)
        if args == ("get-state",):
            return validation.Result(0, "device\n")
        if args == ("shell", "getprop", "ro.kernel.qemu"):
            return validation.Result(0, "0" if self.physical else "1")
        if args == ("shell", "getprop", "ro.build.version.sdk"):
            return validation.Result(0, "34")
        if args[:3] == ("shell", "pm", "path"):
            return validation.Result(0, "" if args[3] == self.missing_package else
                                     "package:/data/app/~~fixture/" + args[3] + "-fixture==/base.apk\n")
        if args == ("shell", "pm", "list", "instrumentation", validation.PACKAGE):
            return validation.Result(0, "" if self.missing_runner else
                                     "instrumentation:" + validation.RUNNER +
                                     " (target=" + validation.PACKAGE + ")\n")
        if args[:2] == ("shell", "wm"):
            kind = args[2]
            if len(args) == 3:
                physical = "1080x1920" if kind == "size" else "420"
                override = getattr(self, kind)
                return validation.Result(0, "Physical " + kind + ": " + physical + "\n" +
                                         ("Override " + kind + ": " + override + "\n" if override else ""))
            if self.restore_failure and args[2:] == ("size", "720x1440"):
                raise validation.ValidationError("adb_command_failed")
            setattr(self, kind, None if args[3] == "reset" else args[3])
        elif args[:2] == ("shell", "settings"):
            verb, name = args[2], args[4]
            if verb == "get":
                return validation.Result(0, self.settings[name])
            self.settings[name] = "null" if verb == "delete" else args[5]
        elif args[:4] == ("shell", "cmd", "uimode", "night"):
            if len(args) == 4:
                return validation.Result(0, "Night mode: " + self.night)
            self.night = args[4]
        elif args[:3] == ("shell", "am", "instrument"):
            if self.fail_instrument:
                raise validation.ValidationError("adb_command_timeout")
            return validation.Result(0, passing_case())
        elif args == ("shell", "rm", "-f", validation.UI_PATH):
            pass
        else:
            raise AssertionError("Unexpected command in offline fake")
        return validation.Result(0, "")


class ProcessAdb:
    def __init__(self, kill_works=True, retain_task=True, different_pid=True):
        self.commands = []
        self.pid = "77"
        self.task = "5"
        self.foreground = True
        self.killed = False
        self.kill_works = kill_works
        self.retain_task = retain_task
        self.different_pid = different_pid

    def run(self, *args, **kwargs):
        self.commands.append(args)
        if args[:3] == ("shell", "am", "start"):
            self.foreground = True
            if self.killed:
                self.pid = "88" if self.different_pid else "77"
                if not self.retain_task:
                    self.task = "6"
            return validation.Result(0, "Status: ok\nActivity: com.jarvis.developer/.MainActivity\n")
        if args == ("shell", "pidof", validation.PACKAGE):
            return validation.Result(0 if self.pid else 1, self.pid or "")
        if args == ("shell", "dumpsys", "activity", "activities"):
            foreground = validation.COMPONENT if self.foreground else "com.android.launcher3/.Launcher"
            return validation.Result(0, "* Hist #0: ActivityRecord{a u0 " + validation.COMPONENT + " t" + self.task + "}\n"
                                     "mResumedActivity: ActivityRecord{a u0 " + foreground + " t" + self.task + "}\n")
        if args == ("shell", "input", "keyevent", "KEYCODE_HOME"):
            self.foreground = False
        elif args == ("shell", "am", "kill", "--user", "current", validation.PACKAGE):
            if self.kill_works:
                self.pid = None
                self.killed = True
        else:
            raise AssertionError("Unexpected process command")
        return validation.Result(0, "")


class FakeUi:
    def __init__(self, fail_prefill=False):
        self.entered = []
        self.fresh_checked = False
        self.fail_prefill = fail_prefill

    def enter(self, hint, sentinel):
        if self.fail_prefill:
            raise validation.ValidationError("prefill_not_observable_password_redaction_unsupported")
        self.entered.append((hint, sentinel))

    def assert_fresh(self):
        self.fresh_checked = True


class ContractTests(unittest.TestCase):
    def test_physical_serial_and_mutable_source_rejected_before_any_adb(self):
        for serial, sha in (("R58M12345", SHA), ("emulator-5554;reboot", SHA),
                            ("emulator-5554", "main"), ("emulator-5554", SHA.upper())):
            with self.subTest(serial=serial), self.assertRaises(validation.ValidationError):
                validation.validate_identity(serial, sha)
        validation.validate_identity("emulator-5554", SHA)

    def test_output_directory_is_new_temporary_and_not_a_symlink(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "evidence"
            self.assertEqual(target, validation.prepare_output(str(target)))
            with self.assertRaises(validation.ValidationError):
                validation.prepare_output(str(target))
            link = Path(temporary) / "link"
            link.symlink_to(target, target_is_directory=True)
            with self.assertRaises(validation.ValidationError):
                validation.prepare_output(str(link / "child"))
        with self.assertRaises(validation.ValidationError):
            validation.prepare_output("relative/path")

    def test_one_complete_native_case_is_required(self):
        validation.parse_instrumentation(passing_case())
        corruptions = (
            "OK (1 test)\nINSTRUMENTATION_CODE: -1\n",
            passing_case().replace("numtests=1", "numtests=0"),
            passing_case().replace("STATUS_CODE: 0", "STATUS_CODE: -3"),
            passing_case().replace("STATUS_CODE: 0", "STATUS_CODE: -2"),
            passing_case().replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"),
            passing_case() + passing_case(),
            passing_case().replace("STATUS: current=1", "STATUS: current=1\nINSTRUMENTATION_STATUS: current=1"),
            passing_case().replace(validation.TEST_CLASS, "other.Class"),
            passing_case() + "INSTRUMENTATION_FAILED: Process crashed\n",
        )
        for text in corruptions:
            with self.subTest(kind=text[:50]), self.assertRaises(validation.ValidationError):
                validation.parse_instrumentation(text)

    def test_malformed_settings_are_rejected_before_mutation(self):
        for name, text in (("font_scale", "1; reboot"), ("font_scale", "10"),
                           ("user_rotation", "4"), ("accelerometer_rotation", "unknown")):
            with self.subTest(name=name), self.assertRaises(validation.ValidationError):
                validation.setting_value(text, name)
        with self.assertRaises(validation.ValidationError):
            validation.display_snapshot("Physical size: 100x200\nOverride size: 1;reboot", "size")
        with self.assertRaises(validation.ValidationError):
            validation.night_snapshot("Night mode: custom")

    def test_fake_physical_device_gets_no_settings_or_cleanup_commands(self):
        adb = ConfigurationAdb(physical=True)
        evidence = validation.execute(adb, SHA)
        self.assertFalse(evidence["passed"])
        self.assertEqual("physical_device_or_unverified_emulator_denied", evidence["failureReason"])
        self.assertEqual([("get-state",), ("shell", "getprop", "ro.kernel.qemu")], adb.commands)

    def test_fixed_app_test_and_target_runner_are_verified_before_settings(self):
        adb = ConfigurationAdb()
        evidence = validation.execute(adb, SHA, process_runner=lambda _: {"passed": True})
        self.assertTrue(evidence["passed"])
        availability = evidence["installedTargets"]
        self.assertTrue(all(availability[key] for key in
                            ("appPackageAvailable", "testPackageAvailable", "runnerAvailable")))
        probes = [("shell", "pm", "path", validation.PACKAGE),
                  ("shell", "pm", "path", validation.TEST_PACKAGE),
                  ("shell", "pm", "list", "instrumentation", validation.PACKAGE)]
        first_display = next(index for index, args in enumerate(adb.commands) if args[:2] == ("shell", "wm"))
        self.assertTrue(all(adb.commands.index(probe) < first_display for probe in probes))
        self.assertFalse(any("/data/app/" in str(value) for value in availability.values()))
        for key in ("appPackageAvailableProbeSha256", "testPackageAvailableProbeSha256", "runnerProbeSha256"):
            self.assertRegex(availability[key], "^[a-f0-9]{64}$")

    def test_android28_and34_monolithic_package_path_shapes_are_accepted(self):
        for path_template in ("/data/app/{package}-fixture==/base.apk",
                              "/data/app/~~fixture==/{package}-fixture==/base.apk"):
            class FixedPackagePaths(ConfigurationAdb):
                def run(self, *args, **kwargs):
                    if args[:3] == ("shell", "pm", "path"):
                        self.commands.append(args)
                        return validation.Result(0, "package:" + path_template.format(package=args[3]) + "\n")
                    return super().run(*args, **kwargs)

            with self.subTest(shape=path_template):
                evidence = {}
                validation.validate_installed_targets(FixedPackagePaths(), evidence)
                self.assertTrue(evidence["appPackageAvailable"] and evidence["testPackageAvailable"]
                                and evidence["runnerAvailable"])

    def test_missing_app_or_test_package_fails_before_any_configuration_mutation(self):
        for package, reason in ((validation.PACKAGE, "developer_app_package_unavailable"),
                                (validation.TEST_PACKAGE, "developer_test_package_unavailable")):
            with self.subTest(package=package):
                adb = ConfigurationAdb(missing_package=package)
                evidence = validation.execute(adb, SHA)
                self.assertFalse(evidence["passed"])
                self.assertEqual("verify_installed_targets", evidence["phase"])
                self.assertEqual(reason, evidence["failureReason"])
                self.assertEqual([], evidence["profiles"])
                self.assertFalse(any(args[:2] == ("shell", "wm") or "instrument" in args or "rm" in args
                                     for args in adb.commands))
                self.assertFalse(any("install" in args for args in adb.commands))

    def test_missing_runner_fails_before_any_configuration_mutation(self):
        adb = ConfigurationAdb(missing_runner=True)
        evidence = validation.execute(adb, SHA)
        self.assertEqual("developer_instrumentation_runner_unavailable", evidence["failureReason"])
        self.assertTrue(evidence["installedTargets"]["appPackageAvailable"])
        self.assertTrue(evidence["installedTargets"]["testPackageAvailable"])
        self.assertFalse(evidence["installedTargets"]["runnerAvailable"])
        self.assertFalse(any(args[:2] == ("shell", "wm") or "instrument" in args for args in adb.commands))

    def test_unexpected_package_path_or_registry_cannot_substitute_for_fixed_targets(self):
        for target, output in (("path", "package:/system/app/Developer/base.apk\n"),
                               ("path", "package:/data/app/../base.apk\n"),
                               ("path", "package:/data/app/fixture/base.apk\npackage:/data/app/other/base.apk\n"),
                               ("runner", "instrumentation:" + validation.RUNNER + " (target=other.package)\n"),
                               ("runner", "instrumentation:other.test/Runner (target=" + validation.PACKAGE + ")\n")):
            class UnexpectedTarget(ConfigurationAdb):
                def run(self, *args, **kwargs):
                    result = super().run(*args, **kwargs)
                    if (target == "path" and args == ("shell", "pm", "path", validation.PACKAGE)) or (
                            target == "runner" and args == ("shell", "pm", "list", "instrumentation", validation.PACKAGE)):
                        return validation.Result(0, output)
                    return result

            with self.subTest(target=target, output=output):
                adb = UnexpectedTarget()
                evidence = validation.execute(adb, SHA)
                self.assertFalse(evidence["passed"])
                self.assertIn(evidence["failureReason"], ("unexpected_developer_package_path",
                                                        "unexpected_developer_instrumentation_registry"))
                self.assertFalse(any(args[:2] == ("shell", "wm") for args in adb.commands))

    def test_nonzero_native_failure_retains_only_classification_exit_and_output_hash(self):
        private_output = "Unable to find instrumentation info for: ComponentInfo{" + validation.RUNNER + "}\nprivate fixture output"
        digest = hashlib.sha256(private_output.encode()).hexdigest()

        class MissingInstrumentation(ConfigurationAdb):
            def run(self, *args, **kwargs):
                if args[:3] == ("shell", "am", "instrument"):
                    self.commands.append(args)
                    classification = validation.classify_command_failure(args, private_output.encode())
                    raise validation.AdbCommandError(classification, 1, digest, validation.command_kind(args))
                return super().run(*args, **kwargs)

        adb = MissingInstrumentation()
        evidence = validation.execute(adb, SHA)
        self.assertFalse(evidence["passed"])
        self.assertEqual("developer_instrumentation_runner_unavailable", evidence["failureReason"])
        self.assertEqual(digest, evidence["profiles"][0]["instrumentationOutputSha256"])
        self.assertEqual(1, evidence["profiles"][0]["instrumentationExitCode"])
        self.assertEqual("native_instrumentation_case", evidence["commandFailure"]["commandKind"])
        self.assertTrue(evidence["configurationRestored"])
        self.assertTrue(evidence["uiXmlRemoved"])
        self.assertNotIn(private_output, json.dumps(evidence))
        self.assertNotIn("private fixture output", json.dumps(evidence))

    def test_nonzero_empty_package_probe_is_diagnosed_without_attempting_install(self):
        digest = hashlib.sha256(b"").hexdigest()

        class PackageRemoved(ConfigurationAdb):
            def run(self, *args, **kwargs):
                if args == ("shell", "pm", "path", validation.TEST_PACKAGE):
                    self.commands.append(args)
                    raise validation.AdbCommandError(validation.classify_command_failure(args, b""),
                                                     1, digest, validation.command_kind(args))
                return super().run(*args, **kwargs)

        adb = PackageRemoved()
        evidence = validation.execute(adb, SHA)
        self.assertEqual("developer_test_package_unavailable", evidence["failureReason"])
        self.assertEqual(digest, evidence["commandFailure"]["outputSha256"])
        self.assertEqual("fixed_package_path_probe", evidence["commandFailure"]["commandKind"])
        self.assertEqual([], evidence["profiles"])
        self.assertFalse(any(args[:2] == ("shell", "wm") or "install" in args or "instrument" in args
                             for args in adb.commands))

    def test_failure_classification_is_command_specific_and_never_echoes_output(self):
        native = ("shell", "am", "instrument", "-w", validation.RUNNER)
        fixtures = (("Unable to find instrumentation info for: private", "developer_instrumentation_runner_unavailable"),
                    ("Unable to find instrumentation target package: private", "developer_instrumentation_target_unavailable"),
                    ("java.lang.SecurityException: private", "developer_instrumentation_permission_denied"),
                    ("INSTRUMENTATION_ABORTED: Process crashed private", "developer_instrumentation_process_failed"),
                    ("error: device offline", "adb_transport_unavailable"),
                    ("unknown failure private", "adb_command_failed"))
        for output, expected in fixtures:
            with self.subTest(expected=expected):
                self.assertEqual(expected, validation.classify_command_failure(native, output.encode()))
                self.assertNotIn("private", expected)
        self.assertEqual("adb_command_failed", validation.classify_command_failure(
            ("shell", "cat", validation.UI_PATH), fixtures[0][0].encode()))

    def test_adb_nonzero_exit_captures_raw_byte_hash_without_exposing_output(self):
        raw = b"unknown private output\xff"
        process, selector = MagicMock(), MagicMock()
        process.poll.return_value = 1
        process.returncode = 1
        selector.__enter__.return_value = selector
        selector.get_map.side_effect = ({1: True}, {1: True}, {})
        selected_file = SimpleNamespace(fileno=lambda: 9)
        selector.select.return_value = [(SimpleNamespace(fileobj=selected_file), None)]
        with patch.object(validation.subprocess, "Popen", return_value=process), \
                patch.object(validation.selectors, "DefaultSelector", return_value=selector), \
                patch.object(validation.os, "read", side_effect=(raw, b"")):
            adb = validation.Adb("emulator-5554", executable="fake-adb")
            with self.assertRaises(validation.AdbCommandError) as caught:
                adb.run("shell", "am", "instrument", "-w", validation.RUNNER)
        error = caught.exception
        self.assertEqual("adb_command_failed", str(error))
        self.assertEqual(hashlib.sha256(raw).hexdigest(), error.output_sha256)
        self.assertEqual(1, error.exit_code)
        self.assertFalse(hasattr(error, "text"))
        self.assertNotIn("private", json.dumps(error.evidence()))
        process.stdout.close.assert_called_once()

    def test_all_four_profiles_restore_original_override_and_missing_setting(self):
        adb = ConfigurationAdb()
        evidence = validation.execute(adb, SHA, process_runner=lambda _: {"passed": True})
        self.assertTrue(evidence["passed"])
        self.assertTrue(evidence["configurationRestored"])
        self.assertTrue(evidence["uiXmlRemoved"])
        self.assertEqual(4, len(evidence["profiles"]))
        self.assertEqual("720x1440", adb.size)
        self.assertIsNone(adb.density)
        self.assertEqual({"font_scale": "null", "accelerometer_rotation": "1", "user_rotation": "2"}, adb.settings)
        self.assertEqual("auto", adb.night)
        instruments = [args for args in adb.commands if args[:3] == ("shell", "am", "instrument")]
        self.assertEqual(4, len(instruments))
        self.assertTrue(all(validation.TEST_CLASS in args for args in instruments))
        self.assertTrue(all("DeveloperDeviceValidationTest" not in " ".join(args) for args in instruments))
        self.assertIn(("shell", "settings", "delete", "system", "font_scale"), adb.commands)
        self.assertFalse(any(token in {"install", "install-multiple", "force-stop", "pull", "push", "curl", "wget"}
                             for args in adb.commands for token in args))

    def test_native_timeout_still_restores_every_snapshot(self):
        adb = ConfigurationAdb(fail_instrument=True)
        called = []
        evidence = validation.execute(adb, SHA, process_runner=lambda _: called.append(True))
        self.assertFalse(evidence["passed"])
        self.assertEqual("adb_command_timeout", evidence["failureReason"])
        self.assertTrue(evidence["configurationRestored"])
        self.assertTrue(evidence["uiXmlRemoved"])
        self.assertEqual([], called)
        self.assertEqual(("720x1440", None, "auto"), (adb.size, adb.density, adb.night))

    def test_second_profile_failure_retains_identity_and_prior_case_evidence(self):
        class SecondProfileFailure(ConfigurationAdb):
            def run(self, *args, **kwargs):
                result = super().run(*args, **kwargs)
                if args[:3] == ("shell", "am", "instrument") and args.count("narrow_large"):
                    return validation.Result(0, passing_case().replace("STATUS_CODE: 0", "STATUS_CODE: -2"))
                return result

        evidence = validation.execute(SecondProfileFailure(), SHA)
        self.assertFalse(evidence["passed"])
        self.assertEqual("native_profiles", evidence["phase"])
        self.assertEqual(["narrow_normal", "narrow_large"], [r["profileId"] for r in evidence["profiles"]])
        first, failed = evidence["profiles"]
        self.assertTrue(first["passed"])
        self.assertFalse(failed["passed"])
        self.assertEqual("native_case", failed["stage"])
        self.assertEqual(validation.TEST_CLASS, first["testClass"])
        self.assertEqual(validation.TEST_METHOD, first["testMethod"])
        self.assertEqual(hashlib.sha256(passing_case().encode()).hexdigest(),
                         first["instrumentationOutputSha256"])
        self.assertTrue(evidence["configurationRestored"])

    def test_failed_restore_is_failure_and_attempts_remaining_restores(self):
        adb = ConfigurationAdb(fail_instrument=True, restore_failure=True)
        evidence = validation.execute(adb, SHA)
        self.assertFalse(evidence["passed"])
        self.assertFalse(evidence["configurationRestored"])
        self.assertEqual("configuration_restore_failed", evidence["failureReason"])
        self.assertIn(("shell", "cmd", "uimode", "night", "auto"), adb.commands)
        self.assertTrue(evidence["uiXmlRemoved"])

    def test_process_phase_failure_is_not_lost_during_successful_cleanup(self):
        adb = ConfigurationAdb()
        def fail(_):
            raise validation.ValidationError("same_launcher_task_not_restored")
        evidence = validation.execute(adb, SHA, process_runner=fail)
        self.assertFalse(evidence["passed"])
        self.assertTrue(evidence["configurationRestored"])
        self.assertEqual("same_launcher_task_not_restored", evidence["failureReason"])
        evidence = validation.execute(ConfigurationAdb(), SHA, process_runner=lambda _: {"passed": False})
        self.assertFalse(evidence["passed"])
        self.assertEqual("process_death_not_verified", evidence["failureReason"])

    def test_process_death_requires_old_pid_gone_new_pid_and_same_task(self):
        adb, ui = ProcessAdb(), FakeUi()
        evidence = validation.run_process_death(adb, ui, sleeper=lambda _: None)
        self.assertTrue(evidence["passed"])
        self.assertTrue(ui.fresh_checked)
        self.assertEqual(3, len(ui.entered))
        home = adb.commands.index(("shell", "input", "keyevent", "KEYCODE_HOME"))
        kill = adb.commands.index(("shell", "am", "kill", "--user", "current", validation.PACKAGE))
        self.assertLess(home, kill)
        self.assertFalse(any("force-stop" in args for args in adb.commands))
        self.assertEqual(2, sum(args[:3] == ("shell", "am", "start") for args in adb.commands))

    def test_kill_failure_never_falls_back_to_force_stop(self):
        adb, ui = ProcessAdb(kill_works=False), FakeUi()
        with self.assertRaisesRegex(validation.ValidationError, "background_process_not_killed"):
            validation.run_process_death(adb, ui, sleeper=lambda _: None)
        self.assertFalse(ui.fresh_checked)
        self.assertFalse(any("force-stop" in args for args in adb.commands))

    def test_task_change_or_pid_reuse_cannot_pass_as_process_death(self):
        for kwargs, reason in (({"retain_task": False}, "same_launcher_task_not_restored"),
                               ({"different_pid": False}, "new_process_not_observed")):
            with self.subTest(kwargs=kwargs), self.assertRaisesRegex(validation.ValidationError, reason):
                validation.run_process_death(ProcessAdb(**kwargs), FakeUi(), sleeper=lambda _: None)

    def test_process_change_during_post_relaunch_scan_cannot_pass(self):
        adb = ProcessAdb()

        class RestartDuringScan(FakeUi):
            def assert_fresh(self):
                super().assert_fresh()
                adb.pid = "99"

        with self.assertRaisesRegex(validation.ValidationError, "process_or_task_changed_during_fresh_ui_scan"):
            validation.run_process_death(adb, RestartDuringScan(), sleeper=lambda _: None)

    def test_resumed_component_requires_the_exact_developer_launcher(self):
        self.assertTrue(validation.foreground_own(
            "topResumedActivity=ActivityRecord{a u0 com.jarvis.developer/.MainActivity t5}"))
        self.assertFalse(validation.foreground_own(
            "mResumedActivity: ActivityRecord{a u0 malicious.com.jarvis.developer/.MainActivity t5}"))
        self.assertFalse(validation.foreground_own(
            "mResumedActivity: ActivityRecord{a u0 com.jarvis.developer/.MainActivityOther t5}"))

    def test_unobservable_prefill_stops_before_background_or_kill(self):
        adb = ProcessAdb()
        with self.assertRaisesRegex(validation.ValidationError, "prefill_not_observable"):
            validation.run_process_death(adb, FakeUi(fail_prefill=True), sleeper=lambda _: None)
        self.assertFalse(any("kill" in args or "KEYCODE_HOME" in args for args in adb.commands))

    def test_ambiguous_pids_and_tasks_fail_closed(self):
        for text in ("77 88", "abc", "77\n88"):
            with self.assertRaises(validation.ValidationError):
                validation.parse_pid(text)
        self.assertIsNone(validation.parse_pid(""))
        with self.assertRaises(validation.ValidationError):
            validation.parse_task("ActivityRecord{a u0 com.jarvis.developer/.MainActivity t5}\n"
                                  "ActivityRecord{b u0 com.jarvis.developer/.MainActivity t6}")
        with self.assertRaises(validation.ValidationError):
            validation.foreground_own("ActivityRecord{a u0 com.jarvis.developer/.MainActivity t5}")
        with self.assertRaises(validation.ValidationError):
            validation.parse_task("ActivityRecord{a u0 malicious.com.jarvis.developer/.MainActivity t5}")

    def test_password_prefill_requires_exact_raw_or_exact_length_mask(self):
        sentinel = validation.FAKE_PROVIDER
        for text in (sentinel, "\u2022" * len(sentinel), "*" * len(sentinel)):
            self.assertTrue(validation.filled_field_proof(node("EditText", text), sentinel))
        for text in ("", validation.PROVIDER_HINT, "\u2022", "\u2022" * (len(sentinel) - 1)):
            self.assertFalse(validation.filled_field_proof(node("EditText", text), sentinel))

    def test_fresh_scan_requires_all_visible_hints_and_disabled_gates(self):
        ui = validation.HostUi(None)
        ui.top = fresh_nodes
        ui.assert_fresh()
        bad = fresh_nodes()
        next(item for item in bad if item.get("text") == validation.GATE_BUTTONS[0]).set("enabled", "true")
        ui.top = lambda: bad
        with self.assertRaisesRegex(validation.ValidationError, "approval_gate_not_disabled"):
            ui.assert_fresh()

    def test_empty_redacted_password_with_hint_metadata_is_not_empty_proof(self):
        bad = fresh_nodes()
        field = next(item for item in bad if item.get("text") == validation.PROVIDER_HINT)
        field.set("text", "")
        field.set("hint", validation.PROVIDER_HINT)
        ui = validation.HostUi(None)
        ui.top = lambda: bad
        with self.assertRaisesRegex(validation.ValidationError, "empty_field_not_observable"):
            ui.assert_fresh()

    def test_warning_is_unconfirmed_health_and_confirmation_is_rejected(self):
        nodes = fresh_nodes()
        health = nodes[-1]
        health.set("text", "WARNING: 4 rapid launches on unconfirmed version 16. This may be normal relaunching, "
                          "not necessarily a crash. Review recovery before coding or updating.")
        ui = validation.HostUi(None)
        ui.top = lambda: nodes
        ui.assert_fresh()
        health.set("text", "Version 16 confirmed working locally.")
        with self.assertRaisesRegex(validation.ValidationError, "health_was_confirmed"):
            ui.assert_fresh()

    def test_ime_visibility_requires_positive_observation_before_back(self):
        self.assertTrue(validation.ime_shown("mInputShown=true mIsInputViewShown=true"))
        self.assertFalse(validation.ime_shown("mInputShown=false"))
        with self.assertRaises(validation.ValidationError):
            validation.ime_shown("No active keyboard information")

    def test_field_entry_checks_focus_and_never_sends_back_without_shown_ime(self):
        class InputAdb:
            def __init__(self):
                self.commands = []

            def run(self, *args, **kwargs):
                self.commands.append(args)
                if args == ("shell", "dumpsys", "input_method"):
                    return validation.Result(0, "mInputShown=false")
                return validation.Result(0, "")

        adb = InputAdb()
        ui = validation.HostUi(adb)
        ui.navigate = lambda label: None
        field = node("EditText", validation.PROVIDER_HINT, focused="true")
        scroll = node("ScrollView", bounds="[0,0][320,640]")
        ui.find = lambda _: (field, [scroll, field])
        dumps = iter(([field], [node("EditText", "\u2022" * len(validation.FAKE_PROVIDER), focused="true")]))
        ui.dump = lambda: next(dumps)
        ui.enter(validation.PROVIDER_HINT, validation.FAKE_PROVIDER)
        self.assertIn(("shell", "input", "text", validation.FAKE_PROVIDER), adb.commands)
        self.assertFalse(any("KEYCODE_BACK" in command for command in adb.commands))

    def test_changed_focus_aborts_before_typing_any_sentinel(self):
        adb = MagicMock()
        ui = validation.HostUi(adb)
        ui.navigate = lambda label: None
        field = node("EditText", validation.PROVIDER_HINT)
        scroll = node("ScrollView", bounds="[0,0][320,640]")
        ui.find = lambda _: (field, [scroll, field])
        ui.dump = lambda: [node("EditText", validation.TOKEN_HINT, focused="true")]
        with self.assertRaisesRegex(validation.ValidationError, "focused_field_identity_mismatch"):
            ui.enter(validation.PROVIDER_HINT, validation.FAKE_PROVIDER)
        self.assertFalse(any(call.args[:3] == ("shell", "input", "text") for call in adb.run.call_args_list))

    def test_navigation_rejects_engineering_actions_and_accepts_only_read_only_destinations(self):
        adb = MagicMock()
        ui = validation.HostUi(adb)
        with self.assertRaisesRegex(validation.ValidationError, "navigation_action_not_allowlisted"):
            ui.navigate("Create task branch and commit approved files (writes)")
        adb.run.assert_not_called()
        target = node("Button", "Self-development  ▼")
        ui.find = lambda predicate: (target, [target]) if predicate(target) else self.fail("Route identity mismatch")
        ui.navigate("Self-development")
        self.assertEqual(adb.run.call_args.args[:3], ("shell", "input", "tap"))
        adb.reset_mock()
        target.set("content-desc", "Close Self-development tools")
        ui.navigate("Self-development")
        adb.run.assert_not_called()

    def test_subprocess_output_limit_is_enforced_without_exposing_output(self):
        process, selector = MagicMock(), MagicMock()
        process.poll.return_value = 0
        selector.__enter__.return_value = selector
        selector.get_map.return_value = {1: True}
        selected_file = SimpleNamespace(fileno=lambda: 9)
        selector.select.return_value = [(SimpleNamespace(fileobj=selected_file), None)]
        with patch.object(validation.subprocess, "Popen", return_value=process), \
                patch.object(validation.selectors, "DefaultSelector", return_value=selector), \
                patch.object(validation.os, "read", return_value=b"private output" * 20):
            adb = validation.Adb("emulator-5554", executable="fake-adb")
            with self.assertRaisesRegex(validation.ValidationError, "^adb_output_limit_exceeded$"):
                adb.run("get-state", max_bytes=100)
        process.stdout.close.assert_called_once()

    def test_untrusted_xml_and_bounds_are_rejected(self):
        for xml in ("<!DOCTYPE x><hierarchy/>", "not XML", "<hierarchy/>" * 2):
            with self.assertRaises(validation.ValidationError):
                validation.parse_ui(xml)
        with self.assertRaises(validation.ValidationError):
            validation.bounds(node("EditText", bounds="[0,0][0,0]"))

    def test_main_emits_only_sanitized_evidence_and_no_sentinel_or_ui(self):
        evidence = {"passed": False, "failureReason": "prefill_not_observable_password_redaction_unsupported",
                    "sourceCommitSha": SHA}
        with tempfile.TemporaryDirectory() as temporary, patch.object(validation, "Adb"), \
                patch.object(validation, "execute", return_value=evidence):
            output = io.StringIO()
            path = str(Path(temporary) / "result")
            with contextlib.redirect_stdout(output):
                status = validation.main(["--serial", "emulator-5554", "--source-sha", SHA,
                                          "--output-directory", path])
            self.assertEqual(1, status)
            self.assertEqual(evidence, json.loads((Path(path) / "evidence.json").read_text()))
            for sensitive in (validation.FAKE_PROVIDER, validation.FAKE_TOKEN, validation.FAKE_REVIEW,
                              "<hierarchy", "dumpsys", "adb -s"):
                self.assertNotIn(sensitive, output.getvalue())


if __name__ == "__main__":
    unittest.main()
