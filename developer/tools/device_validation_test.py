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


def routed_fresh_ui(settings_nodes=None, tools_nodes=None):
    """Exercise per-page fresh assertions without mocking their state checks."""
    all_nodes = fresh_nodes()
    settings = settings_nodes if settings_nodes is not None else [
        item for item in all_nodes if item.get("class") == "android.widget.ScrollView"
        or item.get("text") == validation.PROVIDER_HINT]
    settings = [*settings, node("TextView", validation.UNSAVED_CONNECTION_STATUS)]
    tools = tools_nodes if tools_nodes is not None else [
        item for item in all_nodes if item.get("text") != validation.PROVIDER_HINT]
    ui = validation.HostUi(None)
    state = {"page": "Settings"}
    ui.navigate = lambda page: state.update(page=page)
    ui.show_tools_controls = lambda: state.update(page="Tools")
    ui.top = lambda: settings if state["page"] == "Settings" else tools
    ui.dump = ui.top
    ui.swipe = lambda *_args, **_kwargs: None
    return ui


class RoutedUi(validation.HostUi):
    """Simulate the current four-page UI; every command is a fixed fake action."""

    def __init__(self, page="Home", expanded=False, saved_connection=False):
        self.page = page
        self.expanded = expanded
        self.saved_connection = saved_connection
        self.commands = []
        self.navigation_actions = []
        self.fields = {}
        self.focus = None
        super().__init__(SimpleNamespace(run=self.run))

    def dump(self):
        result = [node("ScrollView", bounds="[0,0][320,640]",
                       **{"visible-to-user": "true" if label == self.page else "false"})
                  for label in validation.NAVIGATION_LABELS]
        for index, label in enumerate(validation.NAVIGATION_LABELS):
            selected = label == self.page
            result.append(node("Button", label, bounds=f"[{index * 80},640][{(index + 1) * 80},700]",
                               selected="true" if selected else "false",
                               **{"content-desc": label + (", selected" if selected else "")}))
        if self.page == "Settings":
            result.extend([self.field_node(validation.PROVIDER_HINT, 100),
                           node("Button", "Save AI connection", bounds="[0,200][300,240]"),
                           node("Button", "Test AI connection", bounds="[0,250][300,290]"),
                           node("TextView", validation.SAVED_CONNECTION_STATUSES[0]
                                if self.saved_connection else validation.UNSAVED_CONNECTION_STATUS)])
        elif self.page == "Tools":
            result.append(node("Button", validation.HIDE_CONTROLS if self.expanded else validation.SHOW_CONTROLS,
                               bounds="[0,20][300,70]"))
            if self.expanded:
                result.extend([self.field_node(validation.TOKEN_HINT, 100),
                               self.field_node(validation.REVIEW_HINT, 150)])
                result.extend(node("Button", label, enabled="false") for label in validation.GATE_BUTTONS)
                result.extend(node("CheckBox", label, enabled="false", checked="false")
                              for label in validation.GATE_CHECKBOXES)
                result.append(node("TextView", "Version 16 is awaiting your health confirmation."))
        return result

    def field_node(self, hint, top):
        return node("EditText", self.fields.get((self.page, hint), hint),
                    bounds=f"[0,{top}][300,{top + 40}]",
                    focused="true" if self.focus == (self.page, hint) else "false",
                    **{"hint": hint})

    def run(self, *args, **_kwargs):
        self.commands.append(args)
        if args == ("shell", "dumpsys", "input_method"):
            return validation.Result(0, "mInputShown=false")
        if args[:3] == ("shell", "input", "swipe"):
            return validation.Result(0, "")
        if args[:3] == ("shell", "input", "text"):
            if self.focus is None:
                raise AssertionError("Fake input has no focused field")
            self.fields[self.focus] = args[3]
            return validation.Result(0, "")
        if args[:3] == ("shell", "input", "tap"):
            x, y = map(int, args[3:])
            if 640 <= y < 700:
                self.page = validation.NAVIGATION_LABELS[x // 80]
                self.navigation_actions.append(self.page)
                return validation.Result(0, "")
            if self.page == "Tools" and not self.expanded and 20 <= y < 70:
                self.expanded = True
                self.navigation_actions.append(validation.SHOW_CONTROLS)
                return validation.Result(0, "")
            for item in validation.own_nodes(self.dump(), "EditText"):
                left, top, right, bottom = validation.bounds(item)
                if left <= x < right and top <= y < bottom:
                    self.focus = (self.page, item.get("hint"))
                    return validation.Result(0, "")
            raise AssertionError("Fake received a forbidden non-field action")
        raise AssertionError("Unexpected fake routed UI command")


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
        if args == ("shell", "dumpsys", "activity", "processes"):
            font = self.settings["font_scale"]
            return validation.Result(0, "  mGlobalConfiguration: {" + ("1.0" if font == "null" else font) +
                                     " 310mcc260mnc [en_US] ldltr sw320dp w320dp h600dp 320dpi}\n")
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


class FontObserverAdb(ConfigurationAdb):
    """Model AOSP's default-font persistence when absence changes global scale."""

    def __init__(self, global_values, rematerialize_after_delete=False):
        super().__init__()
        self.global_values = iter(global_values)
        self.global_value = 2.0
        self.global_value_at_delete = []
        self.deleted_font = False
        self.final_font_reads = 0
        self.rematerialize_after_delete = rematerialize_after_delete

    def run(self, *args, **kwargs):
        if args == ("shell", "dumpsys", "activity", "processes"):
            self.commands.append(args)
            self.global_value = next(self.global_values, self.global_value)
            return validation.Result(0, "  mGlobalConfiguration: {" + str(self.global_value) + " 310mcc260mnc}\n")
        if args == ("shell", "settings", "delete", "system", "font_scale"):
            self.global_value_at_delete.append(self.global_value)
            self.deleted_font = True
            result = super().run(*args, **kwargs)
            if self.global_value != 1.0:
                self.settings["font_scale"] = "1.0"
            return result
        if args == ("shell", "settings", "get", "system", "font_scale") and self.deleted_font:
            self.final_font_reads += 1
            if self.rematerialize_after_delete and self.final_font_reads >= 2:
                self.settings["font_scale"] = "1.0"
        return super().run(*args, **kwargs)


class ImeAdb:
    """Only fixed IME probes and a positively gated single BACK are simulated."""

    def __init__(self, responses):
        self.responses = iter(responses)
        self.commands = []
        self.budgets = []
        self.deadline = 100

    def run(self, *args, **kwargs):
        self.commands.append(args)
        self.budgets.append(kwargs)
        if args == ("shell", "input", "keyevent", "KEYCODE_BACK"):
            return validation.Result(0, "")
        if args == ("shell", "dumpsys", "input_method"):
            response = next(self.responses)
            if isinstance(response, Exception):
                raise response
            return validation.Result(0, response)
        raise AssertionError("Unexpected fake IME command")


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
    def setUp(self):
        self.sleep_patch = patch.object(validation.time, "sleep", return_value=None)
        self.sleep_patch.start()
        self.addCleanup(self.sleep_patch.stop)

    def test_physical_serial_and_mutable_source_rejected_before_any_adb(self):
        for serial, sha in (("R58M12345", SHA), ("emulator-5554;reboot", SHA),
                            ("emulator-5554", "main"), ("emulator-5554", SHA.upper())):
            with self.subTest(serial=serial), self.assertRaises(validation.ValidationError):
                validation.validate_identity(serial, sha)
        validation.validate_identity("emulator-5554", SHA)

    def test_current_ui_routes_only_settings_tools_and_developer_disclosure_before_fake_input(self):
        ui = RoutedUi()
        for hint, sentinel in ((validation.PROVIDER_HINT, validation.FAKE_PROVIDER),
                                (validation.TOKEN_HINT, validation.FAKE_TOKEN),
                                (validation.REVIEW_HINT, validation.FAKE_REVIEW)):
            ui.enter(hint, sentinel)
        self.assertEqual(["Settings", "Tools", validation.SHOW_CONTROLS], ui.navigation_actions)
        self.assertEqual({("Settings", validation.PROVIDER_HINT): validation.FAKE_PROVIDER,
                          ("Tools", validation.TOKEN_HINT): validation.FAKE_TOKEN,
                          ("Tools", validation.REVIEW_HINT): validation.FAKE_REVIEW}, ui.fields)
        self.assertFalse(ui.saved_connection)
        self.assertFalse(any(action in ("Save AI connection", "Test AI connection", "Confirm this version works")
                             for action in ui.navigation_actions))

    def test_current_ui_fresh_scan_checks_separate_pages_and_reopens_collapsed_tools(self):
        ui = RoutedUi(page="Tools")
        ui.assert_fresh()
        self.assertEqual(["Settings", "Tools", validation.SHOW_CONTROLS], ui.navigation_actions)
        self.assertTrue(ui.expanded)
        self.assertFalse(any(command[:3] == ("shell", "input", "text") for command in ui.commands))

    def test_current_ui_uses_one_visible_active_scroll_with_hidden_page_bounds_retained(self):
        ui = RoutedUi(page="Settings")
        nodes = ui.dump()
        self.assertEqual(4, len([item for item in nodes if item.get("class") == "android.widget.ScrollView"]))
        self.assertEqual(1, len(validation.own_nodes(nodes, "ScrollView")))
        self.assertIsNotNone(ui.active_scroll(nodes))
        nodes.append(node("ScrollView", bounds="[0,0][320,640]", **{"visible-to-user": "true"}))
        with self.assertRaisesRegex(validation.ValidationError, "unique_active_scroll_container_required"):
            ui.active_scroll(nodes)

    def test_current_ui_navigation_rejects_any_action_other_than_fixed_two_tabs(self):
        ui = RoutedUi()
        for forbidden in ("Home", "Projects", "Save AI connection", "Test AI connection",
                          "Confirm this version works", "Check for Developer updates"):
            with self.subTest(label=forbidden), self.assertRaisesRegex(
                    validation.ValidationError, "page_navigation_not_allowlisted"):
                ui.navigate(forbidden)
        self.assertEqual([], ui.commands)

    def test_current_ui_navigation_requires_exact_selected_tab_outside_page(self):
        for corrupt in ("selected", "inside_page", "duplicate"):
            ui = RoutedUi()
            nodes = ui.dump()
            settings = next(item for item in nodes if item.get("text") == "Settings")
            if corrupt == "selected":
                settings.set("selected", "true")
            elif corrupt == "inside_page":
                settings.set("bounds", "[0,10][80,50]")
            else:
                nodes.append(ET.fromstring(ET.tostring(settings)))
            ui.dump = lambda: nodes
            with self.subTest(corrupt=corrupt), self.assertRaises(validation.ValidationError):
                ui.navigate("Settings")
            self.assertEqual([], ui.commands)

    def test_existing_saved_provider_configuration_is_not_overwritten_or_cleared(self):
        ui = RoutedUi(saved_connection=True)
        with self.assertRaisesRegex(validation.ValidationError, "existing_provider_configuration_must_not_be_changed"):
            ui.enter(validation.PROVIDER_HINT, validation.FAKE_PROVIDER)
        self.assertEqual({}, ui.fields)
        self.assertTrue(ui.saved_connection)
        self.assertFalse(any(command[:3] == ("shell", "input", "text") for command in ui.commands))
        self.assertEqual(["Settings"], ui.navigation_actions)

    def test_existing_unsaved_provider_draft_is_not_appended_to_or_cleared(self):
        ui = RoutedUi()
        ui.fields[("Settings", validation.PROVIDER_HINT)] = "existing-unsaved-fixture"
        with self.assertRaisesRegex(validation.ValidationError, "input_field_not_visibly_empty_before_fake_input"):
            ui.enter(validation.PROVIDER_HINT, validation.FAKE_PROVIDER)
        self.assertEqual("existing-unsaved-fixture", ui.fields[("Settings", validation.PROVIDER_HINT)])
        self.assertFalse(any(command[:3] == ("shell", "input", "text") for command in ui.commands))

    def test_fresh_current_ui_rejects_surviving_provider_value_or_saved_state(self):
        for saved in (False, True):
            ui = RoutedUi(page="Tools", saved_connection=saved)
            if not saved:
                ui.fields[("Settings", validation.PROVIDER_HINT)] = validation.FAKE_PROVIDER
            with self.subTest(saved=saved), self.assertRaises(validation.ValidationError):
                ui.assert_fresh()
            self.assertFalse(any(command[:3] == ("shell", "input", "text") for command in ui.commands))

    def test_fixed_profiles_use_exact_logical_viewports_and_zero_rotation(self):
        adb = ConfigurationAdb()
        for profile in validation.PROFILES:
            adb.commands.clear()
            validation.apply_profile(adb, profile)
            expected_pixels = "1280x640" if profile[0] == "landscape_large" else "640x1280"
            self.assertIn(("shell", "wm", "size", expected_pixels), adb.commands)
            self.assertIn(("shell", "wm", "density", "320"), adb.commands)
            self.assertIn(("shell", "settings", "put", "system", "user_rotation", "0"), adb.commands)
            self.assertNotIn(("shell", "settings", "put", "system", "user_rotation", "1"), adb.commands)

    def test_profile_viewport_and_font_arguments_cannot_be_freely_modified(self):
        for index, replacement in ((0, "arbitrary"), (1, "3.0"), (2, "auto"),
                                   (3, "landscape"), (4, "9999"), (5, "480;reboot")):
            modified = list(validation.PROFILES[0])
            modified[index] = replacement
            adb = ConfigurationAdb()
            with self.subTest(index=index), self.assertRaisesRegex(validation.ValidationError, "profile_not_allowlisted"):
                validation.apply_profile(adb, tuple(modified))
            self.assertEqual([], adb.commands)

    def test_numeric_native_diagnostics_require_fixed_profile_expected_value_and_bounded_integer(self):
        for dimension, expected, observed, unit in (("width", 1280, 640, "px"),
                                                     ("height", 640, 1280, "px"),
                                                     ("density", 320, 420, "dpi")):
            text = ("INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: Applied full-display "
                    + dimension + " for landscape_large expected:<" + str(expected) + "> but was:<" + str(observed) + ">\n")
            diagnostic = validation.native_failure_evidence(text)
            self.assertEqual([{"property": "full_display_" + dimension, "profileId": "landscape_large",
                               "expected": expected, "observed": observed, "unit": unit}],
                             diagnostic["numericConfigurationAssertions"])

    def test_numeric_native_diagnostics_reject_malicious_prefix_suffix_overlimit_and_wrong_expected(self):
        messages = (
            "private-fixture Applied full-display width for landscape_large expected:<1280> but was:<640>",
            "Applied full-display width for landscape_large expected:<1280> but was:<640> private-fixture",
            "Applied full-display width for landscape_large expected:<1280> but was:<99999>",
            "Applied full-display width for landscape_large expected:<640> but was:<1280>",
            "Applied full-display width for unknown_profile expected:<1280> but was:<640>",
            "Applied full-display width for landscape_large expected:<1280> but was:<-1>",
            "Applied full-display density for landscape_large expected:<320> but was:<1001>")
        for message in messages:
            diagnostic = validation.native_failure_evidence(
                "INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: " + message + "\n")
            with self.subTest(message=message):
                self.assertNotIn("numericConfigurationAssertions", diagnostic)
                self.assertNotIn("private-fixture", json.dumps(diagnostic))

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
        original_restore = validation.restore
        with patch.object(validation, "restore", side_effect=lambda target, saved:
                          original_restore(target, saved, sleeper=lambda _: None)):
            evidence = validation.execute(adb, SHA)
        self.assertFalse(evidence["passed"])
        self.assertFalse(evidence["configurationRestored"])
        self.assertEqual("adb_command_timeout", evidence["failureReason"])
        self.assertEqual({"reason": "adb_command_timeout", "phase": "native_profiles"},
                         evidence["primaryFailure"])
        self.assertEqual("configuration_restore_failed", evidence["cleanupFailures"][0]["reason"])
        self.assertEqual(["size"], evidence["cleanupFailures"][0]["details"]["commandFailedSettingNames"])
        self.assertIn(("shell", "cmd", "uimode", "night", "auto"), adb.commands)
        self.assertTrue(evidence["uiXmlRemoved"])

    def test_restore_waits_for_delayed_snapshot_convergence_without_repeating_commands(self):
        adb = ConfigurationAdb()
        adb.settings["font_scale"] = "1.0"
        saved = validation.snapshot(adb)
        delayed = dict(saved, user_rotation="1")
        adb.commands.clear()
        sleeper = MagicMock()
        with patch.object(validation, "snapshot", side_effect=(delayed, saved)) as snapshots:
            details = validation.restore(adb, saved, sleeper=sleeper, clock=lambda: 10)
        self.assertTrue(details["verified"])
        self.assertEqual(2, details["snapshotAttempts"])
        self.assertEqual({}, details["mismatchedSettings"])
        self.assertEqual(2, snapshots.call_count)
        self.assertEqual(6, len(adb.commands))
        self.assertEqual(6, len(set(adb.commands)))
        sleeper.assert_called_once_with(0.25)

    def test_restore_persistent_mismatch_is_bounded_and_null_is_not_rotation_zero(self):
        adb = ConfigurationAdb()
        adb.settings["font_scale"] = "1.0"
        saved = validation.snapshot(adb)
        saved["user_rotation"] = "null"
        observed = dict(saved, user_rotation="0")
        adb.commands.clear()
        with patch.object(validation, "snapshot", return_value=observed) as snapshots, \
                self.assertRaises(validation.ConfigurationRestoreError) as caught:
            validation.restore(adb, saved, sleeper=lambda _: None, clock=lambda: 10)
        details = caught.exception.details
        self.assertFalse(details["verified"])
        self.assertEqual(8, details["snapshotAttempts"])
        self.assertEqual(8, snapshots.call_count)
        self.assertEqual({"user_rotation": {"expected": None, "observed": "0"}},
                         details["mismatchedSettings"])
        self.assertEqual(6, len(adb.commands))
        self.assertIn(("shell", "settings", "delete", "system", "user_rotation"), adb.commands)

    def test_restore_retries_only_snapshot_reads_after_transient_parse_failure(self):
        adb = ConfigurationAdb()
        adb.settings["font_scale"] = "1.0"
        saved = validation.snapshot(adb)
        adb.commands.clear()
        with patch.object(validation, "snapshot", side_effect=(
                validation.ValidationError("unsupported_display_snapshot"), saved)):
            details = validation.restore(adb, saved, sleeper=lambda _: None, clock=lambda: 10)
        self.assertTrue(details["verified"])
        self.assertEqual(2, details["snapshotAttempts"])
        self.assertNotIn("snapshotFailureReason", details)
        self.assertEqual(6, len(adb.commands))

    def test_restore_snapshot_settling_has_separate_time_budget(self):
        adb = ConfigurationAdb()
        adb.settings["font_scale"] = "1.0"
        saved = validation.snapshot(adb)
        adb.deadline = 100
        observed_deadlines = []

        def observe(target):
            observed_deadlines.append(target.deadline)
            return saved

        with patch.object(validation, "snapshot", side_effect=observe):
            validation.restore(adb, saved, sleeper=lambda _: None, clock=lambda: 10)
        self.assertEqual([14], observed_deadlines)
        self.assertEqual(100, adb.deadline)

    def test_missing_font_restoration_converges_global_default_before_one_exact_absence_delete(self):
        adb = FontObserverAdb([2.0, 1.0, 1.0])
        saved = validation.snapshot(adb)
        adb.commands.clear()
        details = validation.restore(adb, saved, sleeper=lambda _: None, clock=lambda: 10)
        preparation = details["missingFontScalePreparation"]
        self.assertTrue(details["verified"] and preparation["verified"])
        self.assertEqual(3, preparation["observations"])
        self.assertEqual(2, preparation["matchingObservations"])
        self.assertEqual([1.0], adb.global_value_at_delete)
        self.assertEqual("null", adb.settings["font_scale"])
        self.assertEqual(2, details["snapshotAttempts"])
        put = ("shell", "settings", "put", "system", "font_scale", "1.0")
        delete = ("shell", "settings", "delete", "system", "font_scale")
        self.assertEqual(1, adb.commands.count(put))
        self.assertEqual(1, adb.commands.count(delete))
        global_indices = [index for index, command in enumerate(adb.commands)
                          if command == ("shell", "dumpsys", "activity", "processes")]
        self.assertLess(adb.commands.index(put), global_indices[0])
        self.assertLess(global_indices[-1], adb.commands.index(delete))

    def test_missing_font_preparation_never_converges_fails_closed_after_bounded_observation(self):
        adb = FontObserverAdb([2.0] * 8)
        saved = validation.snapshot(adb)
        with self.assertRaises(validation.ConfigurationRestoreError) as caught:
            validation.restore(adb, saved, sleeper=lambda _: None, clock=lambda: 10)
        details = caught.exception.details
        self.assertFalse(details["verified"])
        self.assertFalse(details["missingFontScalePreparation"]["verified"])
        self.assertEqual(8, details["missingFontScalePreparation"]["observations"])
        self.assertEqual({"expected": None, "observed": "1.0"}, details["mismatchedSettings"]["font_scale"])
        self.assertEqual(1, adb.commands.count(("shell", "settings", "delete", "system", "font_scale")))

    def test_delayed_font_rematerialization_after_delete_cannot_pass_a_single_null_snapshot(self):
        adb = FontObserverAdb([1.0, 1.0], rematerialize_after_delete=True)
        saved = validation.snapshot(adb)
        with self.assertRaises(validation.ConfigurationRestoreError) as caught:
            validation.restore(adb, saved, sleeper=lambda _: None, clock=lambda: 10)
        details = caught.exception.details
        self.assertTrue(details["missingFontScalePreparation"]["verified"])
        self.assertFalse(details["verified"])
        self.assertEqual(8, details["snapshotAttempts"])
        self.assertEqual({"expected": None, "observed": "1.0"}, details["mismatchedSettings"]["font_scale"])
        self.assertEqual(1, adb.commands.count(("shell", "settings", "delete", "system", "font_scale")))

    def test_global_font_probe_requires_one_validated_numeric_configuration_and_redacts_other_text(self):
        for raw in ("no configuration private-fixture", "mGlobalConfiguration: {4.0 private-fixture}",
                    "mGlobalConfiguration: {1.0x private-fixture}",
                    "mGlobalConfiguration: {1.0 first}\nmGlobalConfiguration: {1.0 second}"):
            fake = SimpleNamespace(run=lambda *_args, **_kwargs: validation.Result(0, raw))
            with self.subTest(raw=raw), self.assertRaisesRegex(validation.ValidationError,
                                                              "^global_font_scale_not_observable$"):
                validation.global_font_scale(fake)
        raw = "mGlobalConfiguration: {1.0 private-fixture-not-retained}\n"
        value, digest = validation.global_font_scale(SimpleNamespace(
            run=lambda *_args, **_kwargs: validation.Result(0, raw)))
        self.assertEqual(1.0, value)
        self.assertEqual(hashlib.sha256(raw.encode()).hexdigest(), digest)
        self.assertNotIn("private-fixture", json.dumps((value, digest)))

    def test_native_assertion_diagnostics_allow_only_fixed_category_and_exact_test_frames(self):
        text = ("INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: "
                "Final action bottom must be within the native viewport\n"
                "\tat org.junit.Assert.assertTrue(Assert.java:42)\n"
                "\tat com.jarvis.developer.DeveloperConfigurationDeviceTest."
                + validation.TEST_METHOD + "$lambda$1(DeveloperConfigurationDeviceTest.kt:104)\n")
        diagnostic = validation.native_failure_evidence(text)
        self.assertEqual("assertion", diagnostic["failureType"])
        self.assertEqual("final_action_bottom", diagnostic["assertionCategory"])
        self.assertEqual([{"path": validation.NATIVE_SOURCE_PATH, "method": validation.TEST_METHOD,
                           "line": 104}], diagnostic["sourceFrames"])
        self.assertNotIn("Final action bottom", json.dumps(diagnostic))

    def test_current_page_geometry_helpers_have_fixed_diagnostic_categories_and_source_locations(self):
        cases = (
            ("Requested navigation must be shown", "navigation_visibility", "openPage"),
            ("Requested navigation must respond", "navigation_response", "openPage"),
            ("Requested navigation must be selected", "navigation_selection", "openPage"),
            ("Developer disclosure must be on the visible Tools page", "tools_disclosure_visibility", "openPage"),
            ("Developer disclosure must respond", "tools_disclosure_response", "openPage"),
            ("Final Tools action must be exposed", "tools_action_visibility", "openPage"),
            ("Provider draft must be on the visible Settings page", "settings_provider_visibility", "openPage"),
            ("Current owner UI must retain its four page viewports expected:<4> but was:<3>",
             "page_viewport_count", "visiblePageScroll"),
            ("Exactly one page viewport must be shown expected:<1> but was:<2>",
             "page_viewport_visibility", "visiblePageScroll"),
            ("Requested page navigation must be selected", "page_viewport_selection", "visiblePageScroll"),
            ("Visible viewport must contain the requested page's control", "page_control_containment", "visiblePageScroll"),
            ("Content must fit the actual viewport horizontally", "content_horizontal_containment", "assertHorizontalGeometry"),
            ("Expanded developer controls must be the final Tools section", "final_tools_section", "assertFinalToolsAction"),
            ("Icon action must remain the final expanded Tools child", "final_action_order", "assertFinalToolsAction"),
        )
        for message, category, method in cases:
            text = ("INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: " + message + "\n"
                    "\tat com.jarvis.developer.DeveloperConfigurationDeviceTest." + method
                    + "(DeveloperConfigurationDeviceTest.kt:215)\n")
            diagnostic = validation.native_failure_evidence(text)
            with self.subTest(category=category, method=method):
                self.assertEqual(category, diagnostic["assertionCategory"])
                self.assertEqual([{"path": validation.NATIVE_SOURCE_PATH, "method": method, "line": 215}],
                                 diagnostic["sourceFrames"])
                self.assertNotIn(message, json.dumps(diagnostic))

    def test_current_helper_diagnostics_never_retain_untrusted_message_or_similar_method_names(self):
        private = "private-fixture-key-and-ui-text"
        text = ("INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: Requested navigation must be shown "
                + private + "\n"
                "\tat com.jarvis.developer.DeveloperConfigurationDeviceTest.openPageInjected(DeveloperConfigurationDeviceTest.kt:190)\n"
                "\tat com.jarvis.developer.DeveloperConfigurationDeviceTest.visiblePageScroll(PrivateFile.kt:191)\n"
                "\tat com.jarvis.developer.DeveloperConfigurationDeviceTest.assertFinalToolsAction(DeveloperConfigurationDeviceTest.kt:250)\n")
        diagnostic = validation.native_failure_evidence(text)
        self.assertEqual("navigation_visibility", diagnostic["assertionCategory"])
        self.assertEqual([{"path": validation.NATIVE_SOURCE_PATH, "method": "assertFinalToolsAction", "line": 250}],
                         diagnostic["sourceFrames"])
        encoded = json.dumps(diagnostic)
        for fragment in (private, "openPageInjected", "PrivateFile", "Requested navigation"):
            self.assertNotIn(fragment, encoded)

    def test_unexpected_native_assertion_and_untrusted_frames_are_redacted(self):
        secret = "private-fixture-provider-token"
        text = ("INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: <hierarchy>" + secret + "\n"
                "\tat untrusted." + secret + ".SomeClass.method(PrivateFile.kt:17)\n"
                "\tat com.jarvis.developer.DeveloperConfigurationDeviceTest." + secret + "(DeveloperConfigurationDeviceTest.kt:51)\n"
                "\tat com.jarvis.developer.DeveloperConfigurationDeviceTest.assertEffectiveConfiguration(PrivateFile.kt:52)\n"
                "\tat com.jarvis.developer.DeveloperConfigurationDeviceTest.assertEffectiveConfiguration(DeveloperConfigurationDeviceTest.kt:113)\n")
        diagnostic = validation.native_failure_evidence(text)
        self.assertEqual("unrecognized_assertion", diagnostic["assertionCategory"])
        self.assertEqual([{"path": validation.NATIVE_SOURCE_PATH, "method": "assertEffectiveConfiguration",
                           "line": 113}], diagnostic["sourceFrames"])
        encoded = json.dumps(diagnostic)
        for private in (secret, "PrivateFile", "<hierarchy>", "untrusted"):
            self.assertNotIn(private, encoded)

    def test_configuration_timeout_retains_only_safe_cause_category_and_exact_source_location(self):
        text = ("INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: "
                "Native host configuration/layout did not settle for landscape_large within 10000ms. "
                "Last checkpoint: Applied full-display width for landscape_large\n"
                "\tat com.jarvis.developer.DeveloperConfigurationDeviceTest.awaitEffectiveConfigurationAndNativeLayout(DeveloperConfigurationDeviceTest.kt:167)\n"
                "Caused by: java.lang.AssertionError: Applied full-display width for landscape_large expected:<1280> but was:<640>\n"
                "\tat com.jarvis.developer.DeveloperConfigurationDeviceTest.assertEffectiveConfiguration(DeveloperConfigurationDeviceTest.kt:114)\n")
        diagnostic = validation.native_failure_evidence(text)
        self.assertEqual("configuration_not_settled", diagnostic["assertionCategory"])
        self.assertEqual(["full_display_width"], diagnostic["relatedAssertionCategories"])
        self.assertEqual([167, 114], [frame["line"] for frame in diagnostic["sourceFrames"]])
        self.assertNotIn("expected:<", json.dumps(diagnostic))

    def test_native_diagnostic_frames_are_bounded_and_do_not_invent_assertions(self):
        text = "unrecognized native failure\n" + "".join(
            "\tat com.jarvis.developer.DeveloperConfigurationDeviceTest.assertEffectiveConfiguration(DeveloperConfigurationDeviceTest.kt:"
            + str(line) + ")\n" for line in range(100, 110))
        diagnostic = validation.native_failure_evidence(text)
        self.assertEqual("unrecognized_native_failure", diagnostic["failureType"])
        self.assertEqual("unrecognized", diagnostic["assertionCategory"])
        self.assertEqual(3, len(diagnostic["sourceFrames"]))

    def test_native_primary_failure_survives_both_restoration_and_xml_cleanup_failures(self):
        class NativeAndCleanupFailure(ConfigurationAdb):
            def run(self, *args, **kwargs):
                if args == ("shell", "rm", "-f", validation.UI_PATH):
                    self.commands.append(args)
                    raise validation.ValidationError("adb_command_failed")
                result = super().run(*args, **kwargs)
                if args[:3] == ("shell", "am", "instrument"):
                    return validation.Result(0, "INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: "
                        "Final action bottom must be within the native viewport\n"
                        "\tat com.jarvis.developer.DeveloperConfigurationDeviceTest."
                        + validation.TEST_METHOD + "$lambda$1(DeveloperConfigurationDeviceTest.kt:104)\n")
                return result

        original_restore = validation.restore
        with patch.object(validation, "restore", side_effect=lambda target, saved:
                          original_restore(target, saved, sleeper=lambda _: None)):
            evidence = validation.execute(NativeAndCleanupFailure(restore_failure=True), SHA)
        self.assertFalse(evidence["passed"])
        self.assertEqual("native_case_failed", evidence["failureReason"])
        self.assertEqual({"reason": "native_case_failed", "phase": "native_profiles"}, evidence["primaryFailure"])
        self.assertEqual(["configuration_restore_failed", "temporary_ui_xml_cleanup_failed"],
                         [failure["reason"] for failure in evidence["cleanupFailures"]])
        self.assertEqual("final_action_bottom", evidence["profiles"][0]["nativeFailure"]["assertionCategory"])
        self.assertEqual(104, evidence["profiles"][0]["nativeFailure"]["sourceFrames"][0]["line"])
        self.assertFalse(evidence["configurationRestored"])
        self.assertFalse(evidence["uiXmlRemoved"])

    def test_cleanup_failure_becomes_primary_only_when_main_validation_passed(self):
        class CleanupOnlyFailure(ConfigurationAdb):
            def run(self, *args, **kwargs):
                if args == ("shell", "rm", "-f", validation.UI_PATH):
                    raise validation.ValidationError("adb_command_failed")
                return super().run(*args, **kwargs)

        evidence = validation.execute(CleanupOnlyFailure(), SHA, process_runner=lambda _: {"passed": True})
        self.assertFalse(evidence["passed"])
        self.assertEqual("temporary_ui_xml_cleanup_failed", evidence["failureReason"])
        self.assertEqual("cleanup", evidence["phase"])
        self.assertEqual({"reason": "temporary_ui_xml_cleanup_failed", "phase": "remove_temporary_ui_xml"},
                         evidence["primaryFailure"])

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
        ui = routed_fresh_ui()
        ui.assert_fresh()
        bad = fresh_nodes()
        next(item for item in bad if item.get("text") == validation.GATE_BUTTONS[0]).set("enabled", "true")
        ui = routed_fresh_ui(tools_nodes=bad)
        with self.assertRaisesRegex(validation.ValidationError, "approval_gate_not_disabled"):
            ui.assert_fresh()

    def test_empty_redacted_password_with_hint_metadata_is_not_empty_proof(self):
        bad = fresh_nodes()
        field = next(item for item in bad if item.get("text") == validation.PROVIDER_HINT)
        field.set("text", "")
        field.set("hint", validation.PROVIDER_HINT)
        ui = routed_fresh_ui(settings_nodes=bad)
        with self.assertRaisesRegex(validation.ValidationError, "empty_field_not_observable"):
            ui.assert_fresh()

    def test_warning_is_unconfirmed_health_and_confirmation_is_rejected(self):
        nodes = fresh_nodes()
        health = nodes[-1]
        health.set("text", "WARNING: 4 rapid launches on unconfirmed version 16. This may be normal relaunching, "
                          "not necessarily a crash. Review recovery before coding or updating.")
        ui = routed_fresh_ui(tools_nodes=nodes)
        ui.assert_fresh()
        health.set("text", "Version 16 confirmed working locally.")
        with self.assertRaisesRegex(validation.ValidationError, "health_was_confirmed"):
            ui.assert_fresh()

    def test_ime_visibility_requires_positive_observation_before_back(self):
        self.assertTrue(validation.ime_shown("mInputShown=true mIsInputViewShown=true"))
        self.assertFalse(validation.ime_shown("mInputShown=false"))
        with self.assertRaises(validation.ValidationError):
            validation.ime_shown("No active keyboard information")

    def test_ime_hide_waits_for_delayed_settling_after_exactly_one_back(self):
        adb = ImeAdb(["mInputShown=true", "mInputShown=true", "mInputShown=false mIsInputViewShown=true",
                      "mInputShown=false mIsInputViewShown=false"])
        facts, sleeper = {}, MagicMock()
        validation.hide_observed_ime(adb, facts, sleeper=sleeper, clock=lambda: 10)
        self.assertEqual({"shownObserved": True, "backSent": True, "hiddenObserved": True,
                          "hiddenPolls": 3}, facts)
        self.assertEqual(1, adb.commands.count(("shell", "input", "keyevent", "KEYCODE_BACK")))
        self.assertEqual(4, adb.commands.count(("shell", "dumpsys", "input_method")))
        self.assertEqual(2, sleeper.call_count)
        self.assertEqual(100, adb.deadline)
        self.assertTrue(all(budget["timeout"] <= 1.0 for budget in adb.budgets[2:]))

    def test_ime_persistent_visibility_has_bounded_reads_and_no_second_back(self):
        adb, facts = ImeAdb(["mInputShown=true"] * 13), {}
        with self.assertRaisesRegex(validation.ValidationError, "^ime_did_not_hide$"):
            validation.hide_observed_ime(adb, facts, sleeper=lambda _: None, clock=lambda: 10)
        self.assertFalse(facts["hiddenObserved"])
        self.assertEqual(12, facts["hiddenPolls"])
        self.assertEqual(1, adb.commands.count(("shell", "input", "keyevent", "KEYCODE_BACK")))
        self.assertEqual(100, adb.deadline)

    def test_ime_malformed_observations_fail_closed_before_or_after_the_single_back(self):
        for initial_shown in (False, True):
            responses = (["mInputShown=true"] if initial_shown else []) + [
                "mInputShown=false mIsInputViewShown=unknown private-fixture-output"]
            adb, facts = ImeAdb(responses), {}
            with self.subTest(initial_shown=initial_shown), self.assertRaisesRegex(
                    validation.ValidationError, "^ime_visibility_not_observable$"):
                validation.hide_observed_ime(adb, facts, sleeper=lambda _: None, clock=lambda: 10)
            self.assertEqual(1 if initial_shown else 0,
                             adb.commands.count(("shell", "input", "keyevent", "KEYCODE_BACK")))
            self.assertFalse(facts["hiddenObserved"])
            self.assertNotIn("private-fixture-output", json.dumps(facts))

    def test_ime_conflicting_or_duplicate_flags_never_hide_a_true_observation(self):
        self.assertTrue(validation.ime_shown("mInputShown=false mIsInputViewShown=true"))
        self.assertTrue(validation.ime_shown("mInputShown=false mInputShown=true"))
        self.assertFalse(validation.ime_shown("mInputShown=false mInputShown=false"))
        for malformed in ("mInputShown=false mIsInputViewShown=TRUE", "mInputShown=false mIsInputViewShown=trueish"):
            with self.subTest(malformed=malformed), self.assertRaises(validation.ValidationError):
                validation.ime_shown(malformed)

    def test_ime_empty_missing_or_spaced_assignments_cannot_prove_hidden_before_or_after_back(self):
        malformed_flags = ("mIsInputViewShown=", "mIsInputViewShown= true", "mIsInputViewShown =true",
                           "mIsInputViewShown true", "mIsInputViewShown", "mIsInputViewShown==false")
        for malformed in malformed_flags:
            for initial_shown in (False, True):
                responses = (["mInputShown=true"] if initial_shown else []) + [
                    "mInputShown=false " + malformed + "\nprivate-fixture-output"]
                adb, facts = ImeAdb(responses), {}
                with self.subTest(malformed=malformed, initial_shown=initial_shown), self.assertRaisesRegex(
                        validation.ValidationError, "^ime_visibility_not_observable$"):
                    validation.hide_observed_ime(adb, facts, sleeper=lambda _: None, clock=lambda: 10)
                self.assertFalse(facts["hiddenObserved"])
                self.assertEqual(1 if initial_shown else 0,
                                 adb.commands.count(("shell", "input", "keyevent", "KEYCODE_BACK")))
                self.assertEqual(1 if initial_shown else 0, facts["hiddenPolls"])
                self.assertEqual(100, adb.deadline)
                self.assertNotIn("private-fixture-output", json.dumps(facts))

    def test_ime_probe_timeout_propagates_without_extra_back_or_weaker_hidden_proof(self):
        adb = ImeAdb(["mInputShown=true", validation.ValidationError("adb_command_timeout")])
        facts = {}
        with self.assertRaisesRegex(validation.ValidationError, "^adb_command_timeout$"):
            validation.hide_observed_ime(adb, facts, sleeper=lambda _: None, clock=lambda: 10)
        self.assertEqual(1, facts["hiddenPolls"])
        self.assertFalse(facts["hiddenObserved"])
        self.assertEqual(1, adb.commands.count(("shell", "input", "keyevent", "KEYCODE_BACK")))
        self.assertEqual(100, adb.deadline)

    def test_ime_hidden_poll_stops_at_four_second_deadline_without_repeating_back(self):
        adb, facts, now = ImeAdb(["mInputShown=true", "mInputShown=true"]), {}, [10.0]
        def expire(_pause):
            now[0] += 5
        with self.assertRaisesRegex(validation.ValidationError, "^ime_did_not_hide$"):
            validation.hide_observed_ime(adb, facts, sleeper=expire, clock=lambda: now[0])
        self.assertEqual(1, facts["hiddenPolls"])
        self.assertEqual(1, adb.commands.count(("shell", "input", "keyevent", "KEYCODE_BACK")))
        self.assertEqual(100, adb.deadline)

    def test_delayed_ime_entry_still_requires_and_verifies_exact_focused_prefill(self):
        class DelayedImeUi(RoutedUi):
            def __init__(self):
                super().__init__()
                self.ime_responses = iter(("mInputShown=true", "mInputShown=true", "mInputShown=false"))
            def run(self, *args, **kwargs):
                if args == ("shell", "dumpsys", "input_method"):
                    self.commands.append(args)
                    return validation.Result(0, next(self.ime_responses))
                if args == ("shell", "input", "keyevent", "KEYCODE_BACK"):
                    self.commands.append(args)
                    return validation.Result(0, "")
                return super().run(*args, **kwargs)
        ui = DelayedImeUi()
        ui.enter(validation.PROVIDER_HINT, validation.FAKE_PROVIDER)
        self.assertEqual("complete", ui.entry_stage)
        self.assertEqual(validation.FAKE_PROVIDER, ui.fields[("Settings", validation.PROVIDER_HINT)])
        self.assertEqual(2, ui.ime_facts["hiddenPolls"])
        self.assertTrue(ui.ime_facts["hiddenObserved"])
        self.assertEqual(1, ui.commands.count(("shell", "input", "keyevent", "KEYCODE_BACK")))

    def test_entry_failure_records_only_fixed_field_stage_and_ime_facts_before_any_kill(self):
        class PersistentImeUi(RoutedUi):
            def run(self, *args, **kwargs):
                if args == ("shell", "dumpsys", "input_method"):
                    self.commands.append(args)
                    return validation.Result(0, "mInputShown=true private-fixture-output")
                if args == ("shell", "input", "keyevent", "KEYCODE_BACK"):
                    self.commands.append(args)
                    return validation.Result(0, "")
                return super().run(*args, **kwargs)
        process, ui = ProcessAdb(), PersistentImeUi()
        def process_runner(_adb):
            return validation.run_process_death(process, ui, sleeper=lambda _: None)
        evidence = validation.execute(ConfigurationAdb(), SHA, process_runner=process_runner)
        self.assertFalse(evidence["passed"])
        self.assertEqual("ime_did_not_hide", evidence["failureReason"])
        self.assertEqual({"passed": False, "fieldId": "provider_key", "entryStage": "hide_observed_ime",
                          "ime": {"shownObserved": True, "backSent": True, "hiddenObserved": False,
                                  "hiddenPolls": 12}}, evidence["processDeath"])
        self.assertFalse(any("kill" in command or "KEYCODE_HOME" in command for command in process.commands))
        self.assertTrue(evidence["configurationRestored"] and evidence["uiXmlRemoved"])
        self.assertEqual([], evidence["cleanupFailures"])
        for private in (validation.FAKE_PROVIDER, validation.PROVIDER_HINT, "private-fixture-output", "<hierarchy"):
            self.assertNotIn(private, json.dumps(evidence))

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
        ui.navigate = MagicMock()
        ui.require_unsaved_provider = MagicMock()
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
        ui.navigate = MagicMock()
        ui.require_unsaved_provider = MagicMock()
        field = node("EditText", validation.PROVIDER_HINT)
        scroll = node("ScrollView", bounds="[0,0][320,640]")
        ui.find = lambda _: (field, [scroll, field])
        ui.dump = lambda: [node("EditText", validation.TOKEN_HINT, focused="true")]
        with self.assertRaisesRegex(validation.ValidationError, "focused_field_identity_mismatch"):
            ui.enter(validation.PROVIDER_HINT, validation.FAKE_PROVIDER)
        self.assertFalse(any(call.args[:3] == ("shell", "input", "text") for call in adb.run.call_args_list))

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
