#!/usr/bin/env bash
set -euo pipefail
profile_api="${1:?Expected CI Android API (26 or 34)}"
case "$profile_api" in
    26|34) ;;
    *) echo 'Unsupported compatibility API' >&2; exit 2 ;;
esac
profile_actual_api=$(adb shell getprop ro.build.version.sdk | tr -d '\r\n')
if [ "$profile_actual_api" != "$profile_api" ]; then
    echo 'Compatibility emulator API does not match the requested test gate' >&2
    exit 2
fi
# Recognition biasing requires API 33+. This exact case previously ran only
# on the API 34 voice emulator; API 26 is not a substitute for that gate.
if [ "$profile_api" = 34 ]; then
    timeout 60s adb shell am instrument -w -r -e jarvis_disposable_emulator true -e class com.assistant.core.VoiceProfileUiTest com.assistant.core.test/androidx.test.runner.AndroidJUnitRunner > voice-profile-results.txt 2>&1
    cat voice-profile-results.txt
    grep -Eq 'OK \(1 test\)' voice-profile-results.txt
fi
