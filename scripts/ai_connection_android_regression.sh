#!/usr/bin/env bash
set -euo pipefail

# Reuse the debug-signed main/Beta fixtures produced by updater_android_regression.sh --prepare.
# All app HTTP(S) is intercepted inside this dedicated instrumentation invocation.
fixtures="app/build/updater-regression"

run() {
    if [ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]; then
        echo "Run only on a disposable Android emulator: this harness changes network state." >&2
        exit 1
    fi
    mkdir -p "$fixtures/reports"
    local original_wifi original_data
    original_wifi="$(adb shell settings get global wifi_on | tr -d '\r')"
    original_data="$(adb shell settings get global mobile_data | tr -d '\r')"
    # Expand states now; local variables leave scope before an EXIT trap runs.
    trap "adb shell svc wifi $([ "$original_wifi" = 1 ] && echo enable || echo disable) >/dev/null 2>&1 || true; adb shell svc data $([ "$original_data" = 1 ] && echo enable || echo disable) >/dev/null 2>&1 || true" EXIT
    adb shell input keyevent KEYCODE_WAKEUP
    adb shell wm dismiss-keyguard
    for variant in main beta; do
        local package="com.assistant.core"
        if [ "$variant" = beta ]; then package+=".beta"; fi
        test -f "$fixtures/new/$variant/app.apk"
        test -f "$fixtures/new/$variant/tests.apk"
        adb install -r -t "$fixtures/new/$variant/app.apk"
        adb install -r -t "$fixtures/new/$variant/tests.apk"
        for network in available unavailable; do
            local state="enable"
            if [ "$network" = unavailable ]; then state="disable"; fi
            adb shell svc wifi "$state"
            adb shell svc data "$state"
            local report="$fixtures/reports/$variant-connection-$network.txt"
            timeout 240s adb shell am instrument -w -r \
                -e class com.assistant.core.AiConnectionStatusTest \
                -e connectionNetwork "$network" \
                "$package.test/androidx.test.runner.AndroidJUnitRunner" > "$report" 2>&1
            cat "$report"
            # Android instrumentation may report failures while exiting successfully.
            if ! grep -Eq 'OK \(4 tests\)' "$report"; then
                echo "Connection-status instrumentation failed: $variant/$network" >&2
                exit 1
            fi
        done
    done
    echo "Both variants passed local connection routing online and offline without provider requests."
}

case "${1:-}" in
    --run) run ;;
    *) echo "Usage: bash scripts/ai_connection_android_regression.sh --run" >&2; exit 2 ;;
esac
