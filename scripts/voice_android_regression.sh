#!/usr/bin/env bash
set -euo pipefail
voice_apk=$(find app/build/outputs/apk/voiceCheck -maxdepth 1 -name '*x86_64*.apk' -print -quit)
voice_tests=$(find app/build/outputs/apk/androidTest/voiceCheck -name '*.apk' -print -quit)
test -n "$voice_apk"
test -n "$voice_tests"
adb install -r -t "$voice_apk"
adb install -r -t "$voice_tests"
# Fresh Google emulator setup can create search/assistant windows over the
# activity under test. This changes only the disposable CI emulator.
adb shell pm disable-user --user 0 com.google.android.googlequicksearchbox || true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell settings put system screen_off_timeout 2147483647
adb shell svc power stayon true
adb logcat -c
adb logcat -v threadtime > voice-regression-logcat.txt &
voice_log_pid=$!
trap 'kill "$voice_log_pid" 2>/dev/null || true' EXIT
# Exercise the new real-service bridge before the longer voice suite. A failed
# preflight blocks delivery; the full acceptance gate below is still required.
timeout 120s adb shell am instrument -w -r -e class com.assistant.core.ScreenControlAcceptanceTest com.assistant.core.test/androidx.test.runner.AndroidJUnitRunner > voice-screen-preflight.txt 2>&1
cat voice-screen-preflight.txt
grep -Eq 'OK \(2 tests\)' voice-screen-preflight.txt
set +e
timeout 360s adb shell am instrument -w -r com.assistant.core.test/androidx.test.runner.AndroidJUnitRunner > voice-regression-results.txt 2>&1 &
voice_test_pid=$!
while kill -0 "$voice_test_pid" 2>/dev/null; do
    if grep -q 'Process: com.assistant.core,' voice-regression-logcat.txt; then
        kill "$voice_test_pid" 2>/dev/null || true
        break
    fi
    sleep 2
done
wait "$voice_test_pid"
voice_test_status=$?
set -e
cat voice-regression-results.txt
grep -E 'VoiceRegression|TestRunner|AndroidRuntime|sherpa|com.assistant.core' voice-regression-logcat.txt | tail -n 180 || true
if [ "$voice_test_status" -ne 0 ]; then exit "$voice_test_status"; fi
# am instrument can return exit zero even when a test failed.
grep -Eq 'OK \(45 tests\)' voice-regression-results.txt
