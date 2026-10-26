#!/usr/bin/env bash
# Disposable-emulator acceptance only. No production credential, app data or updater is used.
set -euo pipefail
mkdir -p development-bridge-results
adb install -r developer/build/outputs/apk/debug/developer-debug.apk
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
  timeout "${limit}s" adb shell am instrument -w -r -e class "$class" "$package/androidx.test.runner.AndroidJUnitRunner" > "$destination" 2>&1
  cat "$destination"
  local word=tests
  if [ "$expected" = 1 ]; then word=test; fi
  grep -Fq "OK ($expected $word)" "$destination"
  if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$destination"; then return 1; fi
  printf 'Emulator acceptance duration: %s seconds\n' "$((SECONDS - started))" >> "$destination"
}
run_suite com.assistant.core.test com.assistant.core.services.DevelopmentBridgeAndroidTest 4 development-bridge-results/assistant.txt
run_suite com.jarvis.integration.fixture.test com.jarvis.integration.fixture.UnauthorizedPeerTest 1 development-bridge-results/unknown-peer.txt
run_suite com.assistant.core.beta.test com.jarvis.integration.commercialfixture.CommercialPeerTest 1 development-bridge-results/commercial-peer.txt
run_suite com.assistant.core.test com.assistant.core.services.DevelopmentSourceWorkerAndroidTest 1 development-bridge-results/source-worker.txt 240
adb logcat -d -s AndroidRuntime:E > development-bridge-results/crashes.txt
