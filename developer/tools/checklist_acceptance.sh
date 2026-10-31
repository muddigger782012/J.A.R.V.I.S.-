#!/usr/bin/env bash
# Fixed independent generated app only; no real device, owner app, signing or release operation.
set -euo pipefail
root=developer/build/reports/phase6-checklist-acceptance
results=developer-checklist-device-results
mkdir -p "$results"
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
test "$(adb devices | awk '$2 == "device" { print $1 }' | wc -l)" -eq 1
adb devices | awk '$2 == "device" { print $1 }' | grep -Eq '^emulator-[0-9]+$'
expected_api="${CHECKLIST_API_LEVEL:?Missing expected emulator API level}"
actual_api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$actual_api" == "$expected_api" ]] || { echo "Emulator API mismatch" >&2; exit 1; }
python3 developer/tools/scaffold_build_evidence.py verify-device --root "$root" --platform android --fixture checklist > "$results/preinstall.json.log"
adb install -r -t "$root/android/app/build/outputs/apk/debug/app-debug.apk"
adb install -r -t "$root/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
timeout 90s adb shell am instrument -w -r -e disposableTestDevice true \
  -e class io.example.offlinechecklist.ChecklistDeviceTest#saveCompleteCancelRemovalAndRecreate \
  io.example.offlinechecklist.test/androidx.test.runner.AndroidJUnitRunner > "$results/save-recreate.txt" 2>&1
cat "$results/save-recreate.txt"
grep -Eq '^OK \(1 test\)' "$results/save-recreate.txt"
adb shell am force-stop io.example.offlinechecklist
if adb shell pidof io.example.offlinechecklist > "$results/after-force-stop.txt"; then
  echo "Generated app process still running after force-stop" >&2
  exit 1
fi
test ! -s "$results/after-force-stop.txt"
timeout 90s adb shell am instrument -w -r -e disposableTestDevice true \
  -e class io.example.offlinechecklist.ChecklistDeviceTest#savedTaskSurvivesHostForceStop \
  io.example.offlinechecklist.test/androidx.test.runner.AndroidJUnitRunner > "$results/process-relaunch.txt" 2>&1
cat "$results/process-relaunch.txt"
grep -Eq '^OK \(1 test\)' "$results/process-relaunch.txt"
timeout 90s adb shell am instrument -w -r -e disposableTestDevice true \
  -e class io.example.offlinechecklist.ChecklistDeviceTest#corruptStorageRequiresConfirmedResetBeforeEditing \
  io.example.offlinechecklist.test/androidx.test.runner.AndroidJUnitRunner > "$results/corrupt-recovery.txt" 2>&1
cat "$results/corrupt-recovery.txt"
grep -Eq '^OK \(1 test\)' "$results/corrupt-recovery.txt"
python3 - <<'PY'
import hashlib, json, os, pathlib, re
root = pathlib.Path("developer/build/reports/phase6-checklist-acceptance")
results = pathlib.Path("developer-checklist-device-results")
build = json.loads((root / "android-build-evidence.json").read_text())
assert build["sourceCommitSha"] == os.environ["GITHUB_SHA"]
records = []
for name in ("save-recreate", "process-relaunch", "corrupt-recovery"):
    data = (results / (name + ".txt")).read_bytes()
    assert len(data) < 64000
    text = data.decode()
    assert len(re.findall(r"^OK \(1 test\)", text, re.M)) == 1
    assert not re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed", text)
    records.append(dict(case=name, passed=True, logSha256=hashlib.sha256(data).hexdigest()))
assert (results / "after-force-stop.txt").stat().st_size == 0
evidence = dict(schemaVersion=1, sourceCommitSha=os.environ["GITHUB_SHA"],
                runId=os.environ["GITHUB_RUN_ID"], runAttempt=os.environ["GITHUB_RUN_ATTEMPT"],
                apiLevel=int(os.environ["CHECKLIST_API_LEVEL"]), environment="disposable-emulator",
                ownerDeviceInstalled=False, published=False, hostForceStopVerified=True,
                apk=build["apk"], deviceTestApk=build["deviceTestApk"], results=records)
(results / "evidence.json").write_text(json.dumps(evidence, sort_keys=True, indent=2) + "\n")
print("JARVIS_PHASE6_CHECKLIST_DEVICE=" + json.dumps(evidence, sort_keys=True, separators=(",", ":")))
PY
