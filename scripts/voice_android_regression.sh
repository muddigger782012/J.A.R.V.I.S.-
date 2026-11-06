#!/usr/bin/env bash
set -euo pipefail
voice_apk=$(find app/build/outputs/apk/voiceCheck -maxdepth 1 -name '*x86_64*.apk' -print -quit)
voice_tests=$(find app/build/outputs/apk/androidTest/voiceCheck -name '*.apk' -print -quit)
test -n "$voice_apk"
test -n "$voice_tests"
adb install -r -t "$voice_apk"
adb install -r -t "$voice_tests"
# This is the real independent application from the pinned reference checkout, not a replacement fixture.
manager_apk=.acceptance/connection-manager/connection-manager/build/outputs/apk/debug/connection-manager-debug.apk
test -f "$manager_apk"
adb install -r -t "$manager_apk"
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
# Run the voice capture and conversation regressions before voice-pack installation/playback.
# Keep a minimum passing preflight count; additional acceptance cases must not turn success into failure.
preflight_classes=com.assistant.core.SettingsNavigationUiTest#selfDiagnosticsReviewsOnlyValidatedReferencesAndRestoresScrollWithoutGrantingApproval,com.assistant.core.ScreenControlAcceptanceTest,com.assistant.core.VoiceCaptureAcceptanceTest,com.assistant.core.PersonalFoundationAcceptanceTest#screenReadDiagnosticsStayLocalWithoutLearningOrDeviceExecution,com.assistant.core.PersonalFoundationAcceptanceTest#naturalMediaCommandsExecuteOfflineAndVerifyActualAndroidVolumeChanges,com.assistant.core.PersonalFoundationAcceptanceTest#conflictingVolumeTeachingAndAnOlderPendingApprovalCannotChangeAndroidVolume,com.assistant.core.PersonalFoundationAcceptanceTest#cancellingResearchThroughTheActualServiceRejectsLateSyntheticResults,com.assistant.core.PersonalFoundationAcceptanceTest#conversationalGoalAppEditsStayLocalAndExpiredReviewLabelsCannotBecomeNetworkOffers
timeout 120s adb shell am instrument -w -r -e jarvis_disposable_emulator true -e class "$preflight_classes" com.assistant.core.test/androidx.test.runner.AndroidJUnitRunner > voice-screen-preflight.txt 2>&1
cat voice-screen-preflight.txt
preflight_count=$(sed -nE 's/.*OK \\(([0-9]+) tests\\).*/\\1/p' voice-screen-preflight.txt | tail -n 1)
if [[ ! "$preflight_count" =~ ^[0-9]+$ ]] || (( preflight_count < 22 )); then
    echo "::error::Voice preflight did not report at least 22 passing tests (got: ${preflight_count:-none})."
    exit 1
fi
set +e
timeout 600s adb shell am instrument -w -r -e jarvis_disposable_emulator true com.assistant.core.test/androidx.test.runner.AndroidJUnitRunner > voice-regression-results.txt 2>&1 &
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
# Instrumentation grows as new regressions are added. Accept >=74 successful
# tests rather than a fixed number that rejects an entirely passing suite.
passed_count=$(sed -nE 's/.*OK \(([0-9]+) tests\).*/\1/p' voice-regression-results.txt | tail -n 1)
if [[ ! "$passed_count" =~ ^[0-9]+$ ]] || (( passed_count < 74 )); then
    echo "::error::Voice instrumentation did not report at least 74 passing tests (got: ${passed_count:-none})."
    exit 1
fi
