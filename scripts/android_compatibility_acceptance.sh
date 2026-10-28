#!/usr/bin/env bash
set -euo pipefail
apk=$(find app/build/outputs/apk/debug -maxdepth 1 -name '*x86_64*.apk' -print -quit)
tests=$(find app/build/outputs/apk/androidTest/debug -name '*.apk' -print -quit)
test -n "$apk"
test -n "$tests"
adb install -r -t "$apk"
adb install -r -t "$tests"
timeout 90s adb shell am instrument -w -r -e jarvis_disposable_emulator true -e class com.assistant.core.AndroidCompatibilityAcceptanceTest com.assistant.core.test/androidx.test.runner.AndroidJUnitRunner > android-compatibility-results.txt 2>&1
cat android-compatibility-results.txt
grep -Eq 'OK \(4 tests\)' android-compatibility-results.txt
