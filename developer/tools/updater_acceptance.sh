#!/usr/bin/env bash
set -euo pipefail
adb install -r -t developer/build/outputs/apk/debug/developer-debug.apk
adb install -r -t developer/build/outputs/apk/androidTest/debug/developer-debug-androidTest.apk
timeout 240s adb shell am instrument -w -r -e jarvis_disposable_emulator true -e class com.jarvis.developer.DeveloperUpdaterDeviceTest com.jarvis.developer.test/androidx.test.runner.AndroidJUnitRunner > developer-updater-device-results.txt 2>&1
cat developer-updater-device-results.txt
grep -Eq 'OK \(3 tests\)' developer-updater-device-results.txt
