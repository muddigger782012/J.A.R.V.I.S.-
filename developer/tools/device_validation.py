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
FAKE_PROVIDER = "JARVISFAKEPROVIDERKEYNOTREAL123456"
FAKE_TOKEN = "JARVISFAKEGITHUBTOKENNOTREAL123456789012"
FAKE_REVIEW = "a" * 64
GATE_BUTTONS = (
    "Create task branch and commit approved files (writes)",
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


def snapshot_mismatches(actual, saved):
    # Android's configuration observer can recreate an unset font_scale as its
    # canonical 1.0 default. This restores the same display preference; explicit
    # non-default overrides and every other setting still require exact equality.
    return [key for key in saved if actual[key] != saved[key] and not
            (key == "font_scale" and saved[key] == "null" and actual[key] in ("1", "1.0"))]


def restore(adb, saved):
    failures = []
    # Attempt every restoration even if an earlier command fails.
    commands = [("shell", "wm", kind, saved[kind][1] or "reset") for kind in ("size", "density")]
    commands += [("shell", "settings", "delete", "system", name) if saved[name] == "null"
                 else ("shell", "settings", "put", "system", name, saved[name])
                 for name in ("font_scale", "accelerometer_rotation", "user_rotation")]
    commands.append(("shell", "cmd", "uimode", "night", saved["night"]))
    for args in commands:
        try:
            adb.run(*args)
        except ValidationError:
            failures.append("configuration_restore_command_failed")
    try:
        actual = snapshot(adb)
        require(not snapshot_mismatches(actual, saved), "configuration_restore_mismatch")
    except ValidationError:
        failures.append("configuration_restore_not_verified")
    require(not failures, "configuration_restore_failed")
    return actual["font_scale"] != saved["font_scale"]


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
    _, font, night, orientation, _, _ = profile
    adb.run("shell", "wm", "size", "640x1280")
    adb.run("shell", "wm", "density", "320")
    adb.run("shell", "settings", "put", "system", "font_scale", font)
    adb.run("shell", "settings", "put", "system", "accelerometer_rotation", "0")
    adb.run("shell", "settings", "put", "system", "user_rotation", "0" if orientation == "portrait" else "1")
    adb.run("shell", "cmd", "uimode", "night", night)


def run_profiles(adb, results=None):
    results = [] if results is None else results
    for profile in PROFILES:
        record = {"profileId": profile[0], "passed": False, "nativeCases": 1,
                  "testClass": TEST_CLASS, "testMethod": TEST_METHOD,
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
                # Only a line in our fixed public test class is retained. Raw assertions,
                # stack traces, device text and credential representations remain omitted.
                match = re.search(r"\bat " + re.escape(TEST_CLASS) +
                                  r"\.[A-Za-z0-9_]+\(DeveloperConfigurationDeviceTest\.kt:([1-9][0-9]{0,3})\)",
                                  result.text)
                if match:
                    record["failureTestLine"] = int(match.group(1))
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
        # dumpsys also includes last-paused/destroyed records from earlier profile
        # scenarios. Only the live task history can establish launcher continuity.
        if re.search(r"^\s*\*\s*(?:Hist #[0-9]+:\s*)?ActivityRecord\{", line) and re.search(
                r"(?<![A-Za-z0-9_.])com\.jarvis\.developer/(?:\.MainActivity|com\.jarvis\.developer\.MainActivity)(?:\s|\})", line):
            match = re.search(r"\bt([1-9][0-9]{0,8})\b", line)
            require(match is not None, "unsupported_launcher_task_record")
            records.append(match.group(1))
    require(len(set(records)) == 1, "ambiguous_or_missing_launcher_task")
    for line in text.splitlines():
        if ("mResumedActivity" in line or "topResumedActivity" in line) and re.search(
                r"(?<![A-Za-z0-9_.])com\.jarvis\.developer/(?:\.MainActivity|com\.jarvis\.developer\.MainActivity)(?:\s|\})", line):
            match = re.search(r"\bt([1-9][0-9]{0,8})\b", line)
            require(match is not None and match.group(1) == records[0], "ambiguous_or_missing_launcher_task")
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
    # mInputShown describes the input-method session and can stay true with a
    # hardware keyboard. Prefer the actual IME window's VISIBLE bit (0x2), then
    # the input view, before falling back to older service-only diagnostics.
    windows = re.findall(r"\bmImeWindowVis=(0x[0-9a-fA-F]+|[0-9]+)\b", text)
    if windows:
        visibility = {bool(int(value, 16 if value.startswith("0x") else 10) & 2) for value in windows}
        require(len(visibility) == 1, "ime_visibility_ambiguous")
        return visibility.pop()
    views = re.findall(r"\bmIsInputViewShown=(true|false)\b", text)
    if views:
        require(len(set(views)) == 1, "ime_visibility_ambiguous")
        return views[0] == "true"
    values = re.findall(r"\b(?:mInputShown|mIsInputViewShown)=(true|false)\b", text)
    require(bool(values), "ime_visibility_not_observable")
    return "true" in values


class HostUi:
    """Read-only UI inspection plus swipes and taps into allowlisted text fields."""

    def __init__(self, adb):
        self.adb = adb

    def dump(self):
        self.adb.run("shell", "uiautomator", "dump", "--compressed", UI_PATH,
                     timeout=20, max_bytes=32768)
        return parse_ui(self.adb.run("shell", "cat", UI_PATH, max_bytes=1048576).text)

    def swipe(self, nodes, downward):
        containers = own_nodes(nodes, "ScrollView")
        require(len(containers) == 1, "unique_scroll_container_required")
        left, top, right, bottom = bounds(containers[0])
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

    def navigate(self, label):
        require(label in ("Settings", "Activity", "Advanced tools", "Self-development", "Release verification"),
                "navigation_action_not_allowlisted")
        node, _ = self.find(lambda item: item.get("class") == "android.widget.Button"
                            and item.get("text", "").removesuffix("  ▼").removesuffix("  ▲") == label)
        require(node.get("enabled") == "true", "navigation_action_disabled")
        if node.get("content-desc", "").startswith("Close "):
            return  # This safe accordion is already open.
        left, top, right, bottom = bounds(node)
        self.adb.run("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def enter(self, hint, sentinel):
        require((hint, sentinel) in ((PROVIDER_HINT, FAKE_PROVIDER), (TOKEN_HINT, FAKE_TOKEN),
                                     (REVIEW_HINT, FAKE_REVIEW)), "field_input_not_allowlisted")
        for label in (("Settings", "Advanced tools", "Self-development") if hint == REVIEW_HINT else ("Settings",)):
            self.navigate(label)
        node, nodes = self.find(lambda item: item.get("class") == "android.widget.EditText"
                               and exact_hint(item, hint))
        require(node.get("enabled") == "true", "input_field_disabled")
        field_bounds = bounds(node)
        scrolls = own_nodes(nodes, "ScrollView")
        require(len(scrolls) == 1, "unique_scroll_container_required")
        left, top, right, bottom = field_bounds
        sl, st, sr, sb = bounds(scrolls[0])
        require(sl <= left < right <= sr and st <= top < bottom <= sb,
                "input_field_not_fully_visible")
        self.adb.run("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
        focused = [item for item in own_nodes(self.dump(), "EditText") if item.get("focused") == "true"]
        require(len(focused) == 1 and exact_hint(focused[0], hint), "focused_field_identity_mismatch")
        self.adb.run("shell", "input", "text", sentinel)
        # Establish the actual typed value while the positively identified editor
        # still has focus. Closing an IME may correctly move focus to the page.
        focused = [item for item in own_nodes(self.dump(), "EditText") if item.get("focused") == "true"]
        require(len(focused) == 1 and filled_field_proof(focused[0], sentinel),
                "prefill_not_observable_password_redaction_unsupported")
        if hint == REVIEW_HINT:
            require(focused[0].get("text") == FAKE_REVIEW, "review_fingerprint_prefill_not_exact")
        # Do not send BACK or infer keyboard closure from service bookkeeping.
        # This scenario proves process/task recovery and cleared authority. Actual
        # keyboard/Back behavior is covered by DeveloperGuidedUxDeviceTest with
        # native WindowInsets and real Back key events. The next allowlisted page
        # control remains usable without changing or clearing this entered text.

    def assert_fresh(self):
        # Scan once from top to bottom and retain only presence booleans. No UI
        # text, password representation or hierarchy is written to an artifact.
        required = {hint: False for hint in (PROVIDER_HINT, TOKEN_HINT, REVIEW_HINT)}
        gates = {label: False for label in (*GATE_BUTTONS, *GATE_CHECKBOXES)}
        health = False
        routes = ((), ("Settings",), ("Activity",),
                  ("Settings", "Advanced tools", "Self-development"),
                  ("Settings", "Advanced tools", "Release verification"))
        for route in routes:
            for label in route:
                self.navigate(label)
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
                        if re.fullmatch(r"Version [1-9][0-9]* is awaiting your health confirmation\.", text):
                            health = True
                        elif re.fullmatch(r"WARNING: [0-9]+ rapid launches on unconfirmed version [1-9][0-9]*\. "
                                          r"This may be normal relaunching, not necessarily a crash\. "
                                          r"Review recovery before coding or updating\.", text):
                            health = True
                        require(not re.search(r"confirmed working (?:locally|on this device)", text),
                                "health_was_confirmed")
                if all(required.values()) and all(gates.values()) and health:
                    return
                previous = self.signature(nodes)
                self.swipe(nodes, downward=False)
                nodes = self.dump()
                if self.signature(nodes) == previous:
                    break
        raise ValidationError("fresh_fields_gates_or_unconfirmed_health_not_observable")


def package_pid(adb):
    return parse_pid(adb.run("shell", "pidof", PACKAGE, allow_empty=True).text)


def package_uid(adb):
    text = adb.run("shell", "pm", "list", "packages", "-U", PACKAGE, max_bytes=32768).text
    matches = re.findall(r"(?m)^package:" + re.escape(PACKAGE) + r"\s+uid:([1-9][0-9]{0,8})\s*$", text)
    require(len(matches) == 1 and int(matches[0]) >= 10000, "developer_uid_not_observable")
    return matches[0]


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
    uid = package_uid(adb)
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
    # A recently hidden Activity can still have foreground/transient importance.
    # am kill deliberately leaves those processes alive. Observe a stable eligible
    # background state instead of force-stopping or pretending the kill succeeded.
    stable = 0
    def eligible_background():
        nonlocal stable
        require(package_pid(adb) == old_pid, "process_died_before_am_kill")
        state = adb.run("shell", "am", "get-uid-state", uid, max_bytes=32768).text.strip()
        match = re.fullmatch(r"[0-9]{1,2}\s+\(([A-Z_]+)\)", state)
        require(match is not None, "developer_uid_state_not_observable")
        eligible = match.group(1) in ("LAST_ACTIVITY", "CACHED_ACTIVITY", "CACHED_ACTIVITY_CLIENT", "CACHED_RECENT", "CACHED_EMPTY")
        stable = stable + 1 if eligible else 0
        return stable >= 2
    wait_for(eligible_background, "background_uid_not_killable", attempts=100, pause=0.2, sleeper=sleeper)
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
        evidence["fontScaleDefaultNormalized"] = restore(adb, saved)
        evidence["configurationRestored"] = True
        adb.deadline = clock() + 360
        evidence["phase"] = "process_death"
        evidence["processDeath"] = process_runner(adb)
        require(evidence["processDeath"].get("passed") is True, "process_death_not_verified")
        evidence["phase"] = "complete"
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
                evidence["fontScaleDefaultNormalized"] = restore(adb, saved) or evidence.get("fontScaleDefaultNormalized", False)
                evidence["configurationRestored"] = True
            except ValidationError:
                evidence["configurationRestored"] = False
                if failure:
                    evidence["primaryFailureReason"] = failure
                try:
                    actual = snapshot(adb)
                    evidence["restoreMismatchFields"] = snapshot_mismatches(actual, saved)
                except ValidationError:
                    evidence["restoreReadbackUnavailable"] = True
                failure = "configuration_restore_failed"
            try:
                adb.run("shell", "rm", "-f", UI_PATH)
                evidence["uiXmlRemoved"] = True
            except ValidationError:
                failure = "temporary_ui_xml_cleanup_failed"
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
