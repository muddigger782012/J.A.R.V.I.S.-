#!/usr/bin/env python3
"""Offline checks for an already-installed app in an explicitly approved CI emulator.

This tool never installs an APK, fetches a file, accesses configured credentials, confirms app
health, or presses an action/approval button. Running it changes emulator display
settings temporarily and types fixed, non-secret sentinels into three fields.
Do not invoke it until the exact source and disposable-emulator installation have
been approved separately. It deliberately fails when UI redaction prevents proof.
Only compact evidence JSON is retained. Raw UI XML is temporary on the emulator
and removed in cleanup; raw adb output is inspected only in host memory.
"""

import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET


PACKAGE = "com.jarvis.developer"
TEST_PACKAGE = PACKAGE + ".test"
COMPONENT = PACKAGE + "/.MainActivity"
TEST_CLASS = PACKAGE + ".DeveloperConfigurationDeviceTest"
TEST_METHOD = "appliedHostConfigurationKeepsNativeControlsContainedAndLastActionReachable"
RUNNER = TEST_PACKAGE + "/androidx.test.runner.AndroidJUnitRunner"
UI_PATH = "/data/local/tmp/jarvis-developer-validation.xml"
PROVIDER_HINT = "Paste your API key here (not a model name)"
TOKEN_HINT = "GitHub fine-grained token (not saved)"
REVIEW_HINT = "Enter exact eligible review SHA-256"
NAVIGATION_LABELS = ("Home", "Projects", "Tools", "Settings")
UNSAVED_CONNECTION_STATUS = "No AI connection saved yet."
SAVED_CONNECTION_STATUSES = ("AI connection saved securely on this device.",
                             "Connection saved securely. You can now start a project.")
SHOW_CONTROLS = "Show developer controls"
HIDE_CONTROLS = "Hide developer controls"
FAKE_PROVIDER = "JARVISFAKEPROVIDERKEYNOTREAL123456"
FAKE_TOKEN = "JARVISFAKEGITHUBTOKENNOTREAL123456789012"
FAKE_REVIEW = "a" * 64
GATE_BUTTONS = (
    "Create task branch and commit approved files (writes)",
    "Check CI for this exact task commit (read-only)",
    "Stage this exact source scope for a NEW diff review",
    "Compare baseline, candidate and verified recovery evidence",
    "Record exact approved promotion request (metadata only)",
)
GATE_CHECKBOXES = (
    "I reviewed this exact diff and approve creating its isolated task branch",
    "I reviewed this exact eligible candidate and approve recording its promotion request",
)
PROFILES = (
    ("narrow_normal", "1.0", "no", "portrait", "320", "640"),
    ("narrow_large", "2.0", "no", "portrait", "320", "640"),
    ("narrow_large_night", "2.0", "yes", "portrait", "320", "640"),
    ("landscape_large", "2.0", "no", "landscape", "640", "320"),
)
CONFIGURATION_KEYS = ("size", "density", "font_scale", "accelerometer_rotation", "user_rotation", "night")
NATIVE_SOURCE_PATH = "developer/src/androidTest/java/com/jarvis/developer/DeveloperConfigurationDeviceTest.kt"


class ValidationError(Exception):
    """Only fixed, non-sensitive reason codes may reach the evidence/output."""


class AdbCommandError(ValidationError):
    """Retain bounded failure facts without retaining command output or arguments."""

    def __init__(self, classification, exit_code, output_sha256, command_kind):
        super().__init__(classification)
        self.classification = classification
        self.exit_code = exit_code
        self.output_sha256 = output_sha256
        self.command_kind = command_kind

    def evidence(self):
        return {"classification": self.classification, "exitCode": self.exit_code,
                "outputSha256": self.output_sha256, "commandKind": self.command_kind}


class ConfigurationRestoreError(ValidationError):
    def __init__(self, details):
        super().__init__("configuration_restore_failed")
        self.details = details


def command_kind(args):
    if args in (("shell", "pm", "path", PACKAGE), ("shell", "pm", "path", TEST_PACKAGE)):
        return "fixed_package_path_probe"
    if args == ("shell", "pm", "list", "instrumentation", PACKAGE):
        return "fixed_instrumentation_registry_probe"
    if args[:3] == ("shell", "am", "instrument"):
        return "native_instrumentation_case"
    return "other_bounded_adb_command"


def classify_command_failure(args, output):
    """Map known Android failure shapes to fixed codes; never echo their text."""
    text = output.decode("utf-8", errors="replace")
    if re.search(r"(?mi)^(?:error: )?(?:device .+ not found|device offline|no devices/emulators found)", text):
        return "adb_transport_unavailable"
    if args in (("shell", "pm", "path", PACKAGE), ("shell", "pm", "path", TEST_PACKAGE)) and not text.strip():
        return "developer_app_package_unavailable" if args[-1] == PACKAGE else "developer_test_package_unavailable"
    if args[:3] == ("shell", "am", "instrument"):
        if re.search(r"Unable to find instrumentation (?:info for|component)|No instrumentation found", text):
            return "developer_instrumentation_runner_unavailable"
        if re.search(r"Unable to find instrumentation target package|target package .* not found", text):
            return "developer_instrumentation_target_unavailable"
        if re.search(r"SecurityException|Permission Denial", text):
            return "developer_instrumentation_permission_denied"
        if re.search(r"Process crashed|INSTRUMENTATION_ABORTED", text):
            return "developer_instrumentation_process_failed"
    return "adb_command_failed"


def require(condition, reason):
    if not condition:
        raise ValidationError(reason)


def validate_identity(serial, source_sha):
    require(re.fullmatch(r"emulator-[0-9]{4,5}", serial) is not None,
            "emulator_serial_required")
    require(re.fullmatch(r"[a-f0-9]{40}", source_sha) is not None,
            "immutable_source_sha_required")


def prepare_output(path):
    """Create one new directory under a real temporary root, refusing symlinks."""
    target = Path(path)
    require(target.is_absolute() and re.fullmatch(r"[A-Za-z0-9_-]{1,80}", target.name),
            "invalid_output_directory")
    require(target.parent.is_dir() and not target.exists() and not target.is_symlink(),
            "output_directory_must_be_new")
    require(str(target.parent.resolve()) == str(target.parent), "output_symlink_denied")
    roots = [Path(tempfile.gettempdir()).resolve()]
    runner_temp = os.environ.get("RUNNER_TEMP")
    if runner_temp and Path(runner_temp).is_absolute() and Path(runner_temp).is_dir():
        roots.append(Path(runner_temp).resolve())
    require(any(root in target.parents for root in roots), "output_must_be_temporary")
    try:
        target.mkdir(mode=0o700)
    except OSError:
        raise ValidationError("output_directory_creation_failed") from None
    return target


@dataclass(frozen=True)
class Result:
    code: int
    text: str
    output_sha256: str | None = None


def output_digest(result):
    return result.output_sha256 or hashlib.sha256(result.text.encode("utf-8")).hexdigest()


class Adb:
    """All subprocess output and wall time are bounded, with no host shell."""

    def __init__(self, serial, executable=None, clock=time.monotonic):
        validate_identity(serial, "0" * 40)
        self.serial = serial
        self.executable = executable or shutil.which("adb")
        require(bool(self.executable), "adb_not_available")
        self.clock = clock
        self.deadline = clock() + 900

    def run(self, *args, timeout=20, max_bytes=262144, allow_empty=False):
        require(0 < timeout <= 150 and 0 < max_bytes <= 1048576,
                "invalid_command_budget")
        remaining = min(float(timeout), self.deadline - self.clock())
        require(remaining > 0, "phase_deadline_exceeded")
        # Call sites provide fixed commands and validated numeric values only.
        argv = [self.executable, "-s", self.serial, *args]
        process = None
        output = bytearray()
        end = self.clock() + remaining
        try:
            process = subprocess.Popen(argv, stdin=subprocess.DEVNULL,
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       close_fds=True)
            with selectors.DefaultSelector() as selector:
                selector.register(process.stdout, selectors.EVENT_READ)
                while selector.get_map():
                    left = end - self.clock()
                    require(left > 0, "adb_command_timeout")
                    for key, _ in selector.select(min(left, 0.25)):
                        chunk = os.read(key.fileobj.fileno(), min(65536, max_bytes + 1))
                        if not chunk:
                            selector.unregister(key.fileobj)
                        else:
                            output.extend(chunk)
                            require(len(output) <= max_bytes, "adb_output_limit_exceeded")
                left = end - self.clock()
                require(left > 0, "adb_command_timeout")
                process.wait(timeout=left)
            code = process.returncode
            digest = hashlib.sha256(output).hexdigest()
            if code != 0 and not (allow_empty and code == 1 and not output.strip()):
                raise AdbCommandError(classify_command_failure(args, output), code, digest,
                                      command_kind(args))
            return Result(code, output.decode("utf-8", errors="replace"), digest)
        except (OSError, subprocess.TimeoutExpired):
            raise ValidationError("adb_execution_failed") from None
        finally:
            if process is not None:
                if process.poll() is None:
                    process.kill()
                    try:
                        process.wait(timeout=2)
                    except subprocess.TimeoutExpired:
                        pass
                if process.stdout:
                    process.stdout.close()


def setting_value(text, name):
    value = text.strip()
    patterns = {"font_scale": r"(?:null|[0-9](?:\.[0-9]{1,6})?)",
                "accelerometer_rotation": r"(?:null|0|1)",
                "user_rotation": r"(?:null|[0-3])"}
    require(re.fullmatch(patterns[name], value) is not None, "unsupported_setting_snapshot")
    if name == "font_scale" and value != "null":
        require(0.5 <= float(value) <= 3, "unsupported_font_scale_snapshot")
    return value


def display_snapshot(text, kind):
    pattern = r"[1-9][0-9]{1,4}x[1-9][0-9]{1,4}" if kind == "size" else r"[1-9][0-9]{1,3}"
    lines = text.strip().splitlines()
    physical = re.fullmatch(r"Physical " + kind + r": (" + pattern + r")", lines[0]) if lines else None
    require(physical is not None and len(lines) in (1, 2), "unsupported_display_snapshot")
    override = None
    if len(lines) == 2:
        match = re.fullmatch(r"Override " + kind + r": (" + pattern + r")", lines[1])
        require(match is not None, "unsupported_display_snapshot")
        override = match.group(1)
    return (physical.group(1), override)


def night_snapshot(text):
    match = re.fullmatch(r"Night mode: (auto|yes|no)\s*", text.strip())
    require(match is not None, "unsupported_night_mode_snapshot")
    return match.group(1)


def snapshot(adb):
    result = {kind: display_snapshot(adb.run("shell", "wm", kind).text, kind)
              for kind in ("size", "density")}
    for name in ("font_scale", "accelerometer_rotation", "user_rotation"):
        result[name] = setting_value(adb.run("shell", "settings", "get", "system", name).text, name)
    result["night"] = night_snapshot(adb.run("shell", "cmd", "uimode", "night").text)
    return result


def validate_installed_targets(adb, evidence):
    """Require the fixed app, test APK and matching runner before any setting changes.

    A connected Gradle test task may remove APKs during teardown. This read-only
    preflight diagnoses that state; it never installs, repairs or fetches them.
    Only probe digests and availability booleans enter the retained evidence.
    """
    evidence.update(appPackageAvailable=False, testPackageAvailable=False, runnerAvailable=False)
    for package, key, reason in (
            (PACKAGE, "appPackageAvailable", "developer_app_package_unavailable"),
            (TEST_PACKAGE, "testPackageAvailable", "developer_test_package_unavailable")):
        result = adb.run("shell", "pm", "path", package, max_bytes=32768)
        evidence[key + "ProbeSha256"] = output_digest(result)
        lines = result.text.strip().splitlines()
        require(bool(lines), reason)
        require(len(lines) == 1 and re.fullmatch(
            r"package:/data/app/(?:(?!\.{1,2}/)[A-Za-z0-9_.~+=-]+/){1,3}base\.apk", lines[0]) is not None,
            "unexpected_developer_package_path")
        evidence[key] = True
    # The pm argument is the instrumentation TARGET package, not the test APK.
    result = adb.run("shell", "pm", "list", "instrumentation", PACKAGE, max_bytes=32768)
    evidence["runnerProbeSha256"] = output_digest(result)
    lines = result.text.strip().splitlines()
    require(bool(lines), "developer_instrumentation_runner_unavailable")
    require(lines == ["instrumentation:" + RUNNER + " (target=" + PACKAGE + ")"],
            "unexpected_developer_instrumentation_registry")
    evidence["runnerAvailable"] = True


def safe_configuration_value(name, value):
    """Only already validated numeric/enum state may be retained, never raw output."""
    if name in ("size", "density"):
        physical, override = value
        if name == "size":
            return {"physicalPixels": [int(part) for part in physical.split("x")],
                    "overridePixels": None if override is None else [int(part) for part in override.split("x")]}
        return {"physicalDpi": int(physical), "overrideDpi": None if override is None else int(override)}
    # Preserve missing values distinctly: a missing rotation is not rotation 0.
    if name in ("font_scale", "accelerometer_rotation", "user_rotation"):
        return None if value == "null" else value
    return value


def configuration_mismatches(saved, observed):
    return {name: {"expected": safe_configuration_value(name, saved[name]),
                   "observed": safe_configuration_value(name, observed[name])}
            for name in CONFIGURATION_KEYS if saved[name] != observed[name]}


def global_font_scale(adb):
    output = adb.run("shell", "dumpsys", "activity", "processes", max_bytes=1048576)
    matches = re.findall(r"(?m)^\s*mGlobalConfiguration:\s*\{([0-9](?:\.[0-9]{1,6})?)\s", output.text)
    require(len(matches) == 1 and 0.5 <= float(matches[0]) <= 3,
            "global_font_scale_not_observable")
    return float(matches[0]), output_digest(output)


def settle_default_font_before_absence(adb, details, sleeper, clock):
    preparation = {"requestedFontScale": "1.0", "verified": False, "observations": 0,
                   "matchingObservations": 0, "absenceDeleteIssued": False}
    details["missingFontScalePreparation"] = preparation
    previous_deadline = adb.deadline
    settle_deadline = clock() + 4
    adb.deadline = min(previous_deadline, settle_deadline)
    try:
        for attempt in range(8):
            if clock() >= settle_deadline:
                break
            preparation["observations"] += 1
            try:
                database_value = setting_value(adb.run("shell", "settings", "get", "system", "font_scale").text,
                                               "font_scale")
                global_value, digest = global_font_scale(adb)
                preparation["lastDatabaseValue"] = None if database_value == "null" else database_value
                preparation["lastGlobalValue"] = global_value
                preparation["lastGlobalProbeSha256"] = digest
                preparation.pop("probeFailureReason", None)
                matching = database_value != "null" and float(database_value) == 1.0 and global_value == 1.0
                preparation["matchingObservations"] = preparation["matchingObservations"] + 1 if matching else 0
                if preparation["matchingObservations"] >= 2:
                    preparation["verified"] = True
                    break
            except ValidationError as error:
                preparation["probeFailureReason"] = str(error)
                preparation["matchingObservations"] = 0
            if attempt < 7 and clock() < settle_deadline:
                sleeper(min(0.25, max(0, settle_deadline - clock())))
    finally:
        adb.deadline = previous_deadline
    # Delete only after the explicit default-font preparation. Even a failed
    # preparation gets this one best-effort cleanup; it can never count as pass.
    preparation["absenceDeleteIssued"] = True
    try:
        adb.run("shell", "settings", "delete", "system", "font_scale")
        preparation["absenceDeleteSucceeded"] = True
    except ValidationError:
        preparation["absenceDeleteSucceeded"] = False
        details["commandFailedSettingNames"].append("font_scale")


def restore(adb, saved, sleeper=None, clock=time.monotonic):
    sleeper = time.sleep if sleeper is None else sleeper
    details = {"commandFailedSettingNames": [], "snapshotAttempts": 0,
               "mismatchedSettings": {}, "verified": False}
    # Issue each fixed restoration command once, even when another command fails.
    commands = [(kind, ("shell", "wm", kind, saved[kind][1] or "reset")) for kind in ("size", "density")]
    # AOSP's font observer reads an absent font_scale as 1.0 and persists it if
    # global configuration still differs. First converge to the real default,
    # then delete once and independently verify the original absent state.
    commands += [(name, ("shell", "settings", "put", "system", name, "1.0")
                  if name == "font_scale" and saved[name] == "null"
                  else ("shell", "settings", "delete", "system", name) if saved[name] == "null"
                  else ("shell", "settings", "put", "system", name, saved[name]))
                 for name in ("font_scale", "accelerometer_rotation", "user_rotation")]
    commands.append(("night", ("shell", "cmd", "uimode", "night", saved["night"])))
    for name, args in commands:
        try:
            adb.run(*args)
        except ValidationError:
            details["commandFailedSettingNames"].append(name)

    if saved["font_scale"] == "null":
        settle_default_font_before_absence(adb, details, sleeper, clock)

    # Android may apply display/rotation changes asynchronously. Observe convergence
    # for at most four seconds/eight reads; never repeat a mutation to force a pass.
    previous_deadline = adb.deadline
    settle_deadline = clock() + 4
    adb.deadline = min(previous_deadline, settle_deadline)
    matching_observations = 0
    try:
        for attempt in range(8):
            if clock() >= settle_deadline:
                break
            details["snapshotAttempts"] += 1
            try:
                observed = snapshot(adb)
                details["mismatchedSettings"] = configuration_mismatches(saved, observed)
                details.pop("snapshotFailureReason", None)
                matching_observations = matching_observations + 1 if not details["mismatchedSettings"] else 0
                if matching_observations >= (2 if saved["font_scale"] == "null" else 1):
                    details["verified"] = True
                    break
            except ValidationError as error:
                details["snapshotFailureReason"] = str(error)
                matching_observations = 0
            if attempt < 7 and clock() < settle_deadline:
                sleeper(min(0.25, max(0, settle_deadline - clock())))
    finally:
        adb.deadline = previous_deadline
    if details["commandFailedSettingNames"] or not details["verified"] or (
            saved["font_scale"] == "null" and not details["missingFontScalePreparation"]["verified"]):
        details["rawSnapshotVerified"] = details["verified"]
        details["verified"] = False
        raise ConfigurationRestoreError(details)
    return details


def native_failure_evidence(text):
    """Extract only fixed assertion categories and frames in this exact test file."""
    result = {"failureType": "unrecognized_native_failure", "assertionCategory": "unrecognized",
              "sourceFrames": []}
    assertions = list(re.finditer(r"(?m)^(?:INSTRUMENTATION_STATUS: stack=|Caused by: )?"
                                 r"(?:java\.lang\.AssertionError|org\.junit\.ComparisonFailure)"
                                 r"(?:: ([^\r\n]*))?$", text))[:4]
    if assertions:
        result["failureType"] = "assertion"
        categories = (
            ("Applied full-display density for ", "full_display_density"),
            ("Applied app-resource density for ", "app_resource_density"),
            ("Applied full-display width for ", "full_display_width"),
            ("Applied full-display height for ", "full_display_height"),
            ("Applied font scale for ", "font_scale"),
            ("Applied Android system night mode for ", "system_night_mode"),
            ("Applied orientation for ", "orientation"),
            ("Applied width for ", "available_width"),
            ("Applied height for ", "available_height"),
            ("Native effective configuration did not settle", "configuration_not_settled"),
            ("Native host configuration/layout did not settle", "configuration_not_settled"),
            ("Native viewport did not receive nonzero layout for ", "native_layout_unavailable"),
            ("Exactly one native scroll viewport is required", "native_viewport_count"),
            ("Native scroll viewport must contain one content view", "native_content_count"),
            ("Native scroll viewport must be laid out", "native_viewport_layout"),
            ("Native scroll viewport must have no pending layout", "native_viewport_layout_pending"),
            ("Native scroll viewport must have nonzero width and height", "native_viewport_size"),
            ("Native content must be laid out", "native_content_layout"),
            ("Native content must have no pending layout", "native_content_layout_pending"),
            ("Native content must have nonzero width and height", "native_content_size"),
            ("Requested navigation must be shown", "navigation_visibility"),
            ("Requested navigation must respond", "navigation_response"),
            ("Requested navigation must be selected", "navigation_selection"),
            ("Developer disclosure must be on the visible Tools page", "tools_disclosure_visibility"),
            ("Developer disclosure must respond", "tools_disclosure_response"),
            ("Final Tools action must be exposed", "tools_action_visibility"),
            ("Provider draft must be on the visible Settings page", "settings_provider_visibility"),
            ("Current owner UI must retain its four page viewports", "page_viewport_count"),
            ("Exactly one page viewport must be shown", "page_viewport_visibility"),
            ("Requested page navigation must be selected", "page_viewport_selection"),
            ("Visible viewport must contain the requested page's control", "page_control_containment"),
            ("Content must require vertical scrolling", "vertical_scroll_required"),
            ("Content must fit the actual viewport horizontally", "content_horizontal_containment"),
            ("Visible interactive controls must exist", "interactive_controls_missing"),
            ("Nonzero control width: ", "control_width"),
            ("Nonzero control height: ", "control_height"),
            ("Escaped left content edge: ", "control_left_containment"),
            ("Escaped right content edge: ", "control_right_containment"),
            ("Icon action must remain the final content child", "final_action_order"),
            ("Expanded developer controls must be the final Tools section", "final_tools_section"),
            ("Icon action must remain the final expanded Tools child", "final_action_order"),
            ("Native ScrollView must scroll toward its final action", "final_action_scroll"),
            ("Native ScrollView must have moved downward", "scroll_position"),
            ("Final action top must be within the native viewport", "final_action_top"),
            ("Final action bottom must be within the native viewport", "final_action_bottom"),
            ("Provider credential must remain empty", "provider_field_empty"),
            ("GitHub credential must remain empty", "github_field_empty"),
            ("Geometry checks must not confirm health", "health_unconfirmed"),
        )
        observed_categories = []
        numeric_assertions = []
        for assertion in assertions:
            message = assertion.group(1) or ""
            category = next((category for prefix, category in categories if message.startswith(prefix)),
                            "unrecognized_assertion")
            if any(message.startswith(label) for label in (*GATE_BUTTONS, *GATE_CHECKBOXES)):
                category = "approval_gate"
            if category not in observed_categories:
                observed_categories.append(category)
            numeric = re.fullmatch(
                r"Applied full-display (width|height|density) for "
                r"(narrow_normal|narrow_large|narrow_large_night|landscape_large) "
                r"expected:<([0-9]{1,5})> but was:<([0-9]{1,5})>", message)
            if numeric:
                dimension, profile_id, expected_text, observed_text = numeric.groups()
                profile = next(profile for profile in PROFILES if profile[0] == profile_id)
                expected, observed = int(expected_text), int(observed_text)
                exact_expected = 320 if dimension == "density" else int(profile[4 if dimension == "width" else 5]) * 2
                if expected == exact_expected and 0 <= observed <= (1000 if dimension == "density" else 8192):
                    record = {"property": "full_display_" + dimension, "profileId": profile_id,
                              "expected": expected, "observed": observed,
                              "unit": "dpi" if dimension == "density" else "px"}
                    if record not in numeric_assertions and len(numeric_assertions) < 3:
                        numeric_assertions.append(record)
        result["assertionCategory"] = observed_categories[0]
        result["relatedAssertionCategories"] = observed_categories[1:]
        if numeric_assertions:
            result["numericConfigurationAssertions"] = numeric_assertions
    allowed_methods = (TEST_METHOD, "assertEffectiveConfiguration", "assertAvailableDimension",
                       "awaitNativeLayout", "awaitEffectiveConfigurationAndNativeLayout", "assertNativeLayout",
                       "openPage", "visiblePageScroll", "assertHorizontalGeometry", "assertFinalToolsAction",
                       "assertWithinHorizontalContent",
                       "assertCredentialsEmptyAndApprovalsUnavailable")
    for frame in re.finditer(r"(?m)^\s+at com\.jarvis\.developer\.DeveloperConfigurationDeviceTest\."
                             r"([A-Za-z0-9_$]+)\(DeveloperConfigurationDeviceTest\.kt:([1-9][0-9]{0,3})\)\s*$", text):
        method, line = frame.groups()
        canonical = next((name for name in allowed_methods
                          if method == name or re.fullmatch(re.escape(name) + r"\$lambda\$[0-9]{1,2}", method)), None)
        if canonical is not None:
            record = {"path": NATIVE_SOURCE_PATH, "method": canonical, "line": int(line)}
            if record not in result["sourceFrames"]:
                result["sourceFrames"].append(record)
            if len(result["sourceFrames"]) == 3:
                break
    return result


def parse_instrumentation(text):
    """Accept exactly one named passing case, never an exit-code-only success."""
    require(not re.search(r"INSTRUMENTATION_FAILED|FAILURES!!!|INSTRUMENTATION_ABORTED|"
                          r"INSTRUMENTATION_RESULT: (?:shortMsg|longMsg)=|"
                          r"INSTRUMENTATION_STATUS: stack=", text), "native_case_failed")
    groups, current, final = [], {}, []
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            pair = line[len("INSTRUMENTATION_STATUS: "):].split("=", 1)
            require(len(pair) == 2, "malformed_instrumentation_status")
            key, value = pair
            require(key not in current, "duplicate_instrumentation_field")
            current[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = line[len("INSTRUMENTATION_STATUS_CODE: "):]
            groups.append((code, current))
            current = {}
        elif line.startswith("INSTRUMENTATION_CODE: "):
            final.append(line[len("INSTRUMENTATION_CODE: "):])
    require(not current and len(groups) == 2 and [item[0] for item in groups] == ["1", "0"]
            and final == ["-1"], "native_one_case_pass_required")
    for _, fields in groups:
        require(fields.get("class") == TEST_CLASS and fields.get("test") == TEST_METHOD
                and fields.get("numtests") == "1" and fields.get("current") == "1",
                "native_case_identity_mismatch")
    require(len(re.findall(r"(?m)^OK \(1 test\)\s*$", text)) == 1,
            "native_one_case_summary_required")


def apply_profile(adb, profile):
    require(profile in PROFILES, "profile_not_allowlisted")
    _, font, night, _, width_dp, height_dp = profile
    # Use a fixed real logical viewport, not an unverified sensor rotation. The
    # native test still requires the exact resulting pixels and orientation.
    viewport = str(int(width_dp) * 2) + "x" + str(int(height_dp) * 2)
    adb.run("shell", "wm", "size", viewport)
    adb.run("shell", "wm", "density", "320")
    adb.run("shell", "settings", "put", "system", "font_scale", font)
    adb.run("shell", "settings", "put", "system", "accelerometer_rotation", "0")
    adb.run("shell", "settings", "put", "system", "user_rotation", "0")
    adb.run("shell", "cmd", "uimode", "night", night)


def run_profiles(adb, results=None):
    results = [] if results is None else results
    for profile in PROFILES:
        record = {"profileId": profile[0], "passed": False, "nativeCases": 1,
                  "testClass": TEST_CLASS, "testMethod": TEST_METHOD,
                  "requestedViewportPixels": [int(profile[4]) * 2, int(profile[5]) * 2],
                  "requestedUserRotation": 0,
                  "orientationMethod": "fixed-logical-viewport-not-sensor-rotation",
                  "stage": "apply_configuration"}
        results.append(record)
        apply_profile(adb, profile)
        arguments = ["shell", "am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS]
        for key, value in zip(("profile_id", "expected_font_scale", "expected_night",
                               "expected_orientation", "expected_display_width_dp",
                               "expected_display_height_dp"), profile):
            arguments += ["-e", key, value]
        arguments.append(RUNNER)
        record["stage"] = "native_case"
        result = None
        try:
            result = adb.run(*arguments, timeout=120, max_bytes=262144)
            record["instrumentationOutputSha256"] = output_digest(result)
            record["instrumentationExitCode"] = result.code
            parse_instrumentation(result.text)
        except AdbCommandError as error:
            record.update(instrumentationOutputSha256=error.output_sha256,
                          instrumentationExitCode=error.exit_code,
                          failureClassification=error.classification)
            raise
        except ValidationError as error:
            record["failureClassification"] = str(error)
            if result is not None:
                record["nativeFailure"] = native_failure_evidence(result.text)
            raise
        record.update(passed=True, stage="complete")
    return results


def parse_pid(text):
    text = text.strip()
    require(not text or re.fullmatch(r"[1-9][0-9]{0,9}", text), "ambiguous_package_pid")
    return text or None


def parse_task(text):
    records = []
    for line in text.splitlines():
        if "ActivityRecord{" in line and re.search(
                r"(?<![A-Za-z0-9_.])com\.jarvis\.developer/(?:\.MainActivity|com\.jarvis\.developer\.MainActivity)(?:\s|\})", line):
            match = re.search(r"\bt([1-9][0-9]{0,8})\b", line)
            require(match is not None, "unsupported_launcher_task_record")
            records.append(match.group(1))
    require(len(set(records)) == 1, "ambiguous_or_missing_launcher_task")
    return records[0]


def foreground_own(text):
    lines = [line for line in text.splitlines()
             if "mResumedActivity" in line or "topResumedActivity" in line]
    require(bool(lines), "resumed_activity_not_observable")
    require(all("ActivityRecord{" in line or re.search(r"[:=]\s*null\s*$", line) for line in lines),
            "resumed_activity_not_observable")
    return any(re.search(r"(?<![A-Za-z0-9_.])com\.jarvis\.developer/(?:\.MainActivity|"
                         r"com\.jarvis\.developer\.MainActivity)(?:\s|\})", line)
               for line in lines)


def bounds(node):
    match = re.fullmatch(r"\[([0-9]{1,5}),([0-9]{1,5})\]\[([0-9]{1,5}),([0-9]{1,5})\]",
                         node.get("bounds", ""))
    require(match is not None, "unsupported_ui_bounds")
    left, top, right, bottom = map(int, match.groups())
    require(0 <= left < right <= 10000 and 0 <= top < bottom <= 10000,
            "unsupported_ui_bounds")
    return (left, top, right, bottom)


def parse_ui(text):
    require(len(text.encode("utf-8")) <= 1048576 and "<!" not in text,
            "unsupported_ui_xml")
    try:
        root = ET.fromstring(text)
    except ET.ParseError:
        raise ValidationError("unsupported_ui_xml") from None
    require(root.tag == "hierarchy", "unsupported_ui_xml")
    nodes = list(root.iter("node"))
    require(0 < len(nodes) <= 4096, "unsupported_ui_node_count")
    return nodes


def own_nodes(nodes, klass=None):
    return [node for node in nodes if node.get("package") == PACKAGE
            and (klass is None or node.get("class") == "android.widget." + klass)
            and node.get("visible-to-user", "true") == "true"
            and node.get("bounds") not in (None, "[0,0][0,0]")]


def exact_hint(node, hint):
    return node.get("text") == hint or node.get("hint") == hint or node.get("hint-text") == hint


def filled_field_proof(node, sentinel):
    text = node.get("text", "")
    # Blank password XML or a hint alone cannot prove that a value was present.
    return text == sentinel or (len(text) == len(sentinel) and len(text) > 0
                                and set(text) <= {"*", "\u2022", "\u25cf"})


def ime_shown(text):
    values = re.findall(r"\b(?:mInputShown|mIsInputViewShown)=(true|false)\b", text)
    require(bool(values), "ime_visibility_not_observable")
    return "true" in values


class HostUi:
    """Read-only inspection with fixed page/disclosure navigation and fake-field input."""

    def __init__(self, adb):
        self.adb = adb

    def dump(self):
        self.adb.run("shell", "uiautomator", "dump", "--compressed", UI_PATH,
                     timeout=20, max_bytes=32768)
        return parse_ui(self.adb.run("shell", "cat", UI_PATH, max_bytes=1048576).text)

    @staticmethod
    def active_scroll(nodes):
        containers = own_nodes(nodes, "ScrollView")
        require(len(containers) == 1, "unique_active_scroll_container_required")
        bounds(containers[0])
        return containers[0]

    @staticmethod
    def navigation(nodes):
        scroll = HostUi.active_scroll(nodes)
        _, _, _, scroll_bottom = bounds(scroll)
        tabs = {}
        for label in NAVIGATION_LABELS:
            matches = [node for node in own_nodes(nodes, "Button")
                       if node.get("text") == label and node.get("content-desc") in (label, label + ", selected")]
            require(len(matches) == 1, "fixed_navigation_tabs_not_observable")
            tab = matches[0]
            require(tab.get("enabled") == "true", "fixed_navigation_tab_disabled")
            require(bounds(tab)[1] >= scroll_bottom, "fixed_navigation_tab_not_outside_page")
            tabs[label] = tab
        selected = [label for label, node in tabs.items() if node.get("selected") == "true"
                    and node.get("content-desc") == label + ", selected"]
        require(len(selected) == 1 and all(node.get("selected") in ("true", "false") for node in tabs.values()),
                "selected_page_not_observable")
        require(all((node.get("selected") == "true") == (node.get("content-desc") == label + ", selected")
                    for label, node in tabs.items()), "selected_page_not_observable")
        return tabs, selected[0]

    def navigate(self, page):
        require(page in ("Settings", "Tools"), "page_navigation_not_allowlisted")
        nodes = self.dump()
        tabs, selected = self.navigation(nodes)
        if selected != page:
            left, top, right, bottom = bounds(tabs[page])
            self.adb.run("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
            nodes = self.dump()
            _, selected = self.navigation(nodes)
        require(selected == page, "fixed_page_navigation_failed")
        return nodes

    def show_tools_controls(self):
        self.navigate("Tools")
        node, nodes = self.find(lambda item: item.get("class") == "android.widget.Button"
                                and item.get("text") in (SHOW_CONTROLS, HIDE_CONTROLS))
        require(self.navigation(nodes)[1] == "Tools", "developer_disclosure_wrong_page")
        require(node.get("enabled") == "true", "developer_disclosure_disabled")
        if node.get("text") == SHOW_CONTROLS:
            left, top, right, bottom = bounds(node)
            sl, st, sr, sb = bounds(self.active_scroll(nodes))
            require(sl <= left < right <= sr and st <= top < bottom <= sb,
                    "developer_disclosure_not_fully_visible")
            self.adb.run("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
            node, _ = self.find(lambda item: item.get("class") == "android.widget.Button"
                                and item.get("text") == HIDE_CONTROLS)
        require(node.get("text") == HIDE_CONTROLS, "developer_controls_not_disclosed")

    def require_unsaved_provider(self):
        node, _ = self.find(lambda item: item.get("class") == "android.widget.TextView"
                            and item.get("text") in (UNSAVED_CONNECTION_STATUS, *SAVED_CONNECTION_STATUSES))
        require(node.get("text") == UNSAVED_CONNECTION_STATUS, "existing_provider_configuration_must_not_be_changed")

    def swipe(self, nodes, downward):
        left, top, right, bottom = bounds(self.active_scroll(nodes))
        require(right - left >= 80 and bottom - top >= 160, "scroll_container_too_small")
        x, margin = (left + right) // 2, max(30, (bottom - top) // 5)
        y1, y2 = (top + margin, bottom - margin) if downward else (bottom - margin, top + margin)
        self.adb.run("shell", "input", "swipe", str(x), str(y1), str(x), str(y2), "220")

    @staticmethod
    def signature(nodes):
        return tuple((node.get("class"), node.get("bounds"), node.get("text"))
                     for node in own_nodes(nodes))

    def top(self):
        nodes = self.dump()
        for _ in range(24):
            previous = self.signature(nodes)
            self.swipe(nodes, downward=True)
            nodes = self.dump()
            if self.signature(nodes) == previous:
                return nodes
        raise ValidationError("scroll_top_not_observable")

    def find(self, predicate):
        nodes = self.top()
        for _ in range(25):
            found = [node for node in own_nodes(nodes) if predicate(node)]
            require(len(found) <= 1, "ambiguous_ui_target")
            if found:
                return found[0], nodes
            previous = self.signature(nodes)
            self.swipe(nodes, downward=False)
            nodes = self.dump()
            if self.signature(nodes) == previous:
                break
        raise ValidationError("required_ui_target_not_observable")

    def enter(self, hint, sentinel):
        require((hint, sentinel) in ((PROVIDER_HINT, FAKE_PROVIDER), (TOKEN_HINT, FAKE_TOKEN),
                                     (REVIEW_HINT, FAKE_REVIEW)), "field_input_not_allowlisted")
        if hint == PROVIDER_HINT:
            self.navigate("Settings")
            self.require_unsaved_provider()
        else:
            self.show_tools_controls()
        node, nodes = self.find(lambda item: item.get("class") == "android.widget.EditText"
                               and exact_hint(item, hint))
        require(node.get("enabled") == "true", "input_field_disabled")
        require(node.get("text") == hint, "input_field_not_visibly_empty_before_fake_input")
        field_bounds = bounds(node)
        left, top, right, bottom = field_bounds
        sl, st, sr, sb = bounds(self.active_scroll(nodes))
        require(sl <= left < right <= sr and st <= top < bottom <= sb,
                "input_field_not_fully_visible")
        self.adb.run("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
        focused = [item for item in own_nodes(self.dump(), "EditText") if item.get("focused") == "true"]
        require(len(focused) == 1 and exact_hint(focused[0], hint), "focused_field_identity_mismatch")
        self.adb.run("shell", "input", "text", sentinel)
        # BACK is safe only with positively observed IME visibility; a blind BACK
        # can finish the Activity on emulators using a hardware keyboard.
        if ime_shown(self.adb.run("shell", "dumpsys", "input_method", max_bytes=262144).text):
            self.adb.run("shell", "input", "keyevent", "KEYCODE_BACK")
            require(not ime_shown(self.adb.run("shell", "dumpsys", "input_method",
                                              max_bytes=262144).text), "ime_did_not_hide")
        focused = [item for item in own_nodes(self.dump(), "EditText") if item.get("focused") == "true"]
        require(len(focused) == 1 and filled_field_proof(focused[0], sentinel),
                "prefill_not_observable_password_redaction_unsupported")
        if hint == REVIEW_HINT:
            require(focused[0].get("text") == FAKE_REVIEW, "review_fingerprint_prefill_not_exact")

    def scan_fresh_page(self, field_hints, require_gates=False, require_health=False, require_unsaved=False):
        # Presence comes from each actual visible page. Hidden-page nodes, blank
        # password redaction and absence never establish empty state or approval.
        required = {hint: False for hint in field_hints}
        gates = {label: False for label in (*GATE_BUTTONS, *GATE_CHECKBOXES)} if require_gates else {}
        health = not require_health
        unsaved = not require_unsaved
        nodes = self.top()
        for _ in range(25):
            for node in own_nodes(nodes):
                text, klass = node.get("text", ""), node.get("class", "")
                if klass == "android.widget.EditText":
                    for hint in required:
                        if exact_hint(node, hint):
                            # The exact visible hint, rather than empty redacted
                            # text with hint metadata, is the required empty proof.
                            require(text == hint, "empty_field_not_observable_password_redaction_unsupported")
                            required[hint] = True
                    require(not filled_field_proof(node, FAKE_PROVIDER)
                            and not filled_field_proof(node, FAKE_TOKEN)
                            and text != FAKE_REVIEW, "sentinel_survived_process_death")
                if text in gates:
                    expected = "android.widget.CheckBox" if text in GATE_CHECKBOXES else "android.widget.Button"
                    require(klass == expected and node.get("enabled") == "false", "approval_gate_not_disabled")
                    if text in GATE_CHECKBOXES:
                        require(node.get("checked") == "false", "approval_checkbox_not_cleared")
                    gates[text] = True
                if klass == "android.widget.TextView":
                    if require_unsaved:
                        require(text not in SAVED_CONNECTION_STATUSES, "provider_connection_was_saved")
                        if text == UNSAVED_CONNECTION_STATUS:
                            unsaved = True
                    if re.fullmatch(r"Version [1-9][0-9]* is awaiting your health confirmation\.", text):
                        health = True
                    elif re.fullmatch(r"WARNING: [0-9]+ rapid launches on unconfirmed version [1-9][0-9]*\. "
                                      r"This may be normal relaunching, not necessarily a crash\. "
                                      r"Review recovery before coding or updating\.", text):
                        health = True
                    require(not re.search(r"confirmed working (?:locally|on this device)", text),
                            "health_was_confirmed")
            if all(required.values()) and all(gates.values()) and health and unsaved:
                return
            previous = self.signature(nodes)
            self.swipe(nodes, downward=False)
            nodes = self.dump()
            if self.signature(nodes) == previous:
                break
        raise ValidationError("fresh_fields_gates_or_unconfirmed_health_not_observable")

    def assert_fresh(self):
        self.navigate("Settings")
        self.scan_fresh_page((PROVIDER_HINT,), require_unsaved=True)
        self.show_tools_controls()
        self.scan_fresh_page((TOKEN_HINT, REVIEW_HINT), require_gates=True, require_health=True)


def package_pid(adb):
    return parse_pid(adb.run("shell", "pidof", PACKAGE, allow_empty=True).text)


def activity_state(adb):
    text = adb.run("shell", "dumpsys", "activity", "activities", max_bytes=1048576).text
    return parse_task(text), foreground_own(text)


def launcher(adb):
    text = adb.run("shell", "am", "start", "-W", "-a", "android.intent.action.MAIN",
                   "-c", "android.intent.category.LAUNCHER", "-f", "0x10200000",
                   "-n", COMPONENT, timeout=30).text
    require(re.search(r"(?m)^Status: ok\s*$", text) is not None
            and not re.search(r"(?mi)^Error:|Exception", text), "launcher_start_failed")


def wait_for(predicate, reason, attempts=15, pause=0.4, sleeper=time.sleep):
    for _ in range(attempts):
        if predicate():
            return
        sleeper(pause)
    raise ValidationError(reason)


def run_process_death(adb, ui=None, sleeper=time.sleep):
    ui = ui or HostUi(adb)
    launcher(adb)
    old_pid = package_pid(adb)
    require(old_pid is not None, "launcher_pid_missing")
    task, foreground = activity_state(adb)
    require(foreground, "launcher_not_foreground")
    for hint, sentinel in ((PROVIDER_HINT, FAKE_PROVIDER), (TOKEN_HINT, FAKE_TOKEN),
                            (REVIEW_HINT, FAKE_REVIEW)):
        ui.enter(hint, sentinel)
    require(package_pid(adb) == old_pid and activity_state(adb) == (task, True),
            "process_or_task_changed_before_background")
    adb.run("shell", "input", "keyevent", "KEYCODE_HOME")

    def background():
        current_task, resumed = activity_state(adb)
        require(current_task == task, "launcher_task_changed_before_kill")
        return not resumed

    wait_for(background, "launcher_did_not_background", sleeper=sleeper)
    require(package_pid(adb) == old_pid, "process_died_before_am_kill")
    # This is an Android background-process kill. Never substitute force-stop:
    # that destroys task-restoration semantics and cannot prove process death.
    adb.run("shell", "am", "kill", "--user", "current", PACKAGE)
    wait_for(lambda: package_pid(adb) is None, "background_process_not_killed", sleeper=sleeper)
    require(activity_state(adb) == (task, False), "launcher_task_not_retained_after_kill")
    launcher(adb)
    new_pid = package_pid(adb)
    require(new_pid is not None and new_pid != old_pid, "new_process_not_observed")
    require(activity_state(adb) == (task, True), "same_launcher_task_not_restored")
    ui.assert_fresh()
    require(package_pid(adb) == new_pid and activity_state(adb) == (task, True),
            "process_or_task_changed_during_fresh_ui_scan")
    return {"passed": True, "prefillObserved": True, "backgroundAmKillObserved": True,
            "oldPidGone": True, "newPidObserved": True, "sameTaskRetained": True,
            "fieldsVisiblyEmpty": True, "approvalGatesDisabled": True,
            "healthUnconfirmed": True}


def validate_emulator(adb):
    require(adb.run("get-state").text.strip() == "device", "emulator_not_ready")
    require(adb.run("shell", "getprop", "ro.kernel.qemu").text.strip() == "1",
            "physical_device_or_unverified_emulator_denied")
    api = adb.run("shell", "getprop", "ro.build.version.sdk").text.strip()
    require(api in ("28", "34"), "unapproved_emulator_api")
    return int(api)


def execute(adb, source_sha, clock=time.monotonic, process_runner=run_process_death):
    evidence = {"schemaVersion": 1, "sourceCommitSha": source_sha,
                "scope": "already-installed-disposable-ci-emulator-offline-only",
                "phase": "validate_emulator",
                "passed": False, "installedTargets": {}, "profiles": [], "processDeath": {"passed": False},
                "primaryFailure": None, "cleanupFailures": [],
                "configurationRestored": False, "uiXmlRemoved": False,
                "apkInstalledByThisTool": False, "providerOrPublisherInvoked": False}
    saved = None
    failure = None
    try:
        evidence["apiLevel"] = validate_emulator(adb)
        evidence["phase"] = "verify_installed_targets"
        validate_installed_targets(adb, evidence["installedTargets"])
        saved = snapshot(adb)  # No settings mutation occurs before all snapshots parse.
        adb.deadline = clock() + 660
        evidence["phase"] = "native_profiles"
        run_profiles(adb, evidence["profiles"])
        evidence["phase"] = "restore_before_process_death"
        evidence["restorationBeforeProcessDeath"] = restore(adb, saved)
        evidence["configurationRestored"] = True
        adb.deadline = clock() + 360
        evidence["phase"] = "process_death"
        evidence["processDeath"] = process_runner(adb)
        require(evidence["processDeath"].get("passed") is True, "process_death_not_verified")
        evidence["phase"] = "complete"
    except ConfigurationRestoreError as error:
        failure = str(error)
        evidence["primaryFailure"] = {"reason": failure, "phase": evidence["phase"],
                                      "details": error.details}
    except AdbCommandError as error:
        failure = str(error)
        evidence["commandFailure"] = error.evidence()
    except ValidationError as error:
        failure = str(error)
    finally:
        # A separate bounded cleanup budget remains even after phase deadlines.
        adb.deadline = clock() + 90
        if saved is not None:
            try:
                evidence["cleanupRestoration"] = restore(adb, saved)
                evidence["configurationRestored"] = True
            except ConfigurationRestoreError as error:
                evidence["configurationRestored"] = False
                evidence["cleanupFailures"].append({"reason": str(error),
                    "phase": "restore_configuration", "details": error.details})
            except ValidationError:
                evidence["configurationRestored"] = False
                evidence["cleanupFailures"].append({"reason": "configuration_restore_failed",
                                                     "phase": "restore_configuration"})
            try:
                adb.run("shell", "rm", "-f", UI_PATH)
                evidence["uiXmlRemoved"] = True
            except ValidationError:
                evidence["cleanupFailures"].append({"reason": "temporary_ui_xml_cleanup_failed",
                                                     "phase": "remove_temporary_ui_xml"})
    if failure is not None and evidence["primaryFailure"] is None:
        evidence["primaryFailure"] = {"reason": failure, "phase": evidence["phase"]}
    if failure is None and evidence["cleanupFailures"]:
        first_cleanup = evidence["cleanupFailures"][0]
        failure = first_cleanup["reason"]
        evidence["primaryFailure"] = dict(first_cleanup)
        evidence["phase"] = "cleanup"
    evidence["passed"] = failure is None
    if failure:
        evidence["failureReason"] = failure
    return evidence


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--output-directory", required=True)
    args = parser.parse_args(argv)
    try:
        validate_identity(args.serial, args.source_sha)
        output = prepare_output(args.output_directory)
        evidence = execute(Adb(args.serial), args.source_sha)
        # Exclusive creation prevents accidental replacement even inside the new directory.
        with (output / "evidence.json").open("x", encoding="utf-8") as stream:
            json.dump(evidence, stream, sort_keys=True, separators=(",", ":"))
            stream.write("\n")
        print("JARVIS_DEVELOPER_OFFLINE_NATIVE " + json.dumps(evidence, sort_keys=True, separators=(",", ":")))
        return 0 if evidence["passed"] else 1
    except ValidationError as error:
        print("JARVIS_DEVELOPER_OFFLINE_NATIVE_ERROR " + str(error))
        return 1
    except (OSError, ValueError, TypeError):
        # Exception messages can contain command output, paths or UI contents.
        print("JARVIS_DEVELOPER_OFFLINE_NATIVE_ERROR host_validation_failed")
        return 1


if __name__ == "__main__":
    sys.exit(main())
