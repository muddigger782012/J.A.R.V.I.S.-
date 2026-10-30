#!/usr/bin/env bash
set -euo pipefail
# Retain the existing four compatibility cases as a hard prerequisite.
bash scripts/android_compatibility_acceptance.sh
screen_manager_apk=.acceptance/connection-manager/connection-manager/build/outputs/apk/debug/connection-manager-debug.apk
test -f "$screen_manager_apk"
adb install -r -t "$screen_manager_apk"
screen_probe_class=com.assistant.core.ScreenControlAcceptanceTest#separateConnectionManagerLauncherAndScreenRemainUsableWithoutAutomaticProviderProbes
for screen_probe_round in 1 2 3; do
    screen_probe_result="screen-read-round-${screen_probe_round}.txt"
    timeout 120s adb shell am instrument -w -r -e jarvis_disposable_emulator true -e class "$screen_probe_class" com.assistant.core.test/androidx.test.runner.AndroidJUnitRunner > "$screen_probe_result" 2>&1
    cat "$screen_probe_result"
    grep -Eq 'OK \(1 test\)' "$screen_probe_result"
done
