#!/usr/bin/env bash
set -euo pipefail
adb install -r -t developer/build/outputs/apk/debug/developer-debug.apk
adb install -r -t developer/build/outputs/apk/androidTest/debug/developer-debug-androidTest.apk
timeout 180s adb shell am instrument -w -r -e jarvis_disposable_emulator true -e class com.jarvis.developer.PersonalRepairDeviceTest com.jarvis.developer.test/androidx.test.runner.AndroidJUnitRunner > personal-repair-device-results.txt 2>&1
cat personal-repair-device-results.txt
grep -Eq 'OK \(6 tests\)' personal-repair-device-results.txt
