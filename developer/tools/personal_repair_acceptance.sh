#!/usr/bin/env bash
set -euo pipefail
adb install -r -t developer/build/outputs/apk/debug/developer-debug.apk
adb install -r -t developer/build/outputs/apk/androidTest/debug/developer-debug-androidTest.apk
timeout 240s adb shell am instrument -w -r -e jarvis_disposable_emulator true -e class com.jarvis.developer.PersonalRepairDeviceTest,com.jarvis.developer.DeveloperGuidedUxDeviceTest,com.jarvis.developer.DevelopmentJobControlsDeviceTest,com.jarvis.developer.DevelopmentHistoryDeviceTest com.jarvis.developer.test/androidx.test.runner.AndroidJUnitRunner > personal-repair-device-results.txt 2>&1
cat personal-repair-device-results.txt
grep -Eq 'OK \(18 tests\)' personal-repair-device-results.txt
