#!/usr/bin/env bash
# Disposable-emulator acceptance only. No production credential, app data or updater is used.
set -euo pipefail
# Refuse a physical device before any APK or permission changes.
case "$(adb shell getprop ro.hardware | tr -d '\r')" in
  ranchu|goldfish) ;;
  *) echo 'Disposable Android emulator required.' >&2; exit 1 ;;
esac
mkdir -p development-bridge-results
# Fresh disposable emulator only; no owner account, provider key, audio or private logs are loaded.
installer_cleanup() {
  local acceptance_exit=$?
  trap - EXIT
  # Revoking this permission can terminate Developer on Android 14. Restore it only
  # after the test runner returned, and keep cleanup failures separate and visible.
  if ! adb shell appops set com.jarvis.developer REQUEST_INSTALL_PACKAGES default > development-bridge-results/installer-permission-restoration.txt 2>&1; then
    acceptance_exit=1
  fi
  if ! adb shell appops get com.jarvis.developer REQUEST_INSTALL_PACKAGES >> development-bridge-results/installer-permission-restoration.txt 2>&1; then
    acceptance_exit=1
  fi
  if ! grep -Eq 'REQUEST_INSTALL_PACKAGES: default|No operations' development-bridge-results/installer-permission-restoration.txt; then
    acceptance_exit=1
  fi
  if ! adb logcat -d -b all > development-bridge-results/crashes.txt; then acceptance_exit=1; fi
  exit "$acceptance_exit"
}
trap installer_cleanup EXIT
adb install -r developer/build/outputs/apk/debug/developer-debug.apk
adb install -r developer/build/outputs/apk/androidTest/debug/developer-debug-androidTest.apk
adb install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb install -r integration/test-peer/build/outputs/apk/debug/bridge-test-peer-debug.apk
adb install -r integration/test-peer/build/outputs/apk/androidTest/debug/bridge-test-peer-debug-androidTest.apk
adb install -r integration/commercial-test-peer/build/outputs/apk/debug/bridge-commercial-test-peer-debug.apk
adb install -r integration/commercial-test-peer/build/outputs/apk/androidTest/debug/bridge-commercial-test-peer-debug-androidTest.apk
run_suite() {
  local package="$1" class="$2" expected="$3" destination="$4"
  local started=$SECONDS
  local limit="${5:-120}"
  timeout "${limit}s" adb shell am instrument -w -r -e jarvisDisposable true -e class "$class" "$package/androidx.test.runner.AndroidJUnitRunner" > "$destination" 2>&1
  cat "$destination"
  local word=tests
  if [ "$expected" = 1 ]; then word=test; fi
  grep -Fq "OK ($expected $word)" "$destination"
  if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$destination"; then return 1; fi
  printf 'Emulator acceptance duration: %s seconds\n' "$((SECONDS - started))" >> "$destination"
}
run_suite com.assistant.core.test com.assistant.core.services.DevelopmentBridgeAndroidTest 5 development-bridge-results/assistant.txt
run_suite com.jarvis.integration.fixture.test com.jarvis.integration.fixture.UnauthorizedPeerTest 1 development-bridge-results/unknown-peer.txt
run_suite com.assistant.core.beta.test com.jarvis.integration.commercialfixture.CommercialPeerTest 1 development-bridge-results/commercial-peer.txt
run_suite com.jarvis.developer.test com.jarvis.developer.RepositoryAnalysisRuntimeTest 1 development-bridge-results/source-memory.txt
run_suite com.jarvis.developer.test com.jarvis.developer.EngineeringJobAndroidTest 4 development-bridge-results/engineering-jobs.txt
run_suite com.assistant.core.test com.assistant.core.services.DevelopmentSourceWorkerAndroidTest 1 development-bridge-results/source-worker.txt 240
run_suite com.jarvis.developer.test com.jarvis.developer.ArtifactInstallationAndroidTest 4 development-bridge-results/artifact-installation.txt 180
adb shell am force-stop com.jarvis.developer
run_suite com.jarvis.developer.test com.jarvis.developer.ArtifactInstallationRecoveryAndroidTest 1 development-bridge-results/artifact-recovery.txt 60
# First-launch microphone permission is an explicit disposable-fixture setup,
# matching the existing UI smoke runner. No owner device is ever used here.
adb shell pm grant com.assistant.core android.permission.RECORD_AUDIO
run_suite com.assistant.core.test com.assistant.core.HudGradientCompatibilityTest 1 development-bridge-results/hud-gradient.txt 60
run_suite com.assistant.core.test com.assistant.core.services.DevelopmentArtifactHandoffAndroidTest 2 development-bridge-results/artifact-handoff.txt 120
run_suite com.assistant.core.test com.assistant.core.services.DevelopmentInstallationAndroidTest 4 development-bridge-results/conversational-installation.txt 180
adb logcat -d -s AndroidRuntime:E > development-bridge-results/crashes.txt
