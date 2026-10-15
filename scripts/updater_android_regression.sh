#!/usr/bin/env bash
set -euo pipefail

# These are same-source version/signing fixtures, not historical release APKs.
# They exercise Android archive checks, install -r and real Keystore retention.
# Production-device upgrades still require the owner's original signing key.
fixtures="app/build/updater-regression"

copy_apk() {
    local build_type="$1" abi="$2" destination="$3"
    local candidates=(app/build/outputs/apk/"$build_type"/*"$abi"*.apk)
    if [ "${#candidates[@]}" -ne 1 ] || [ ! -f "${candidates[0]}" ]; then
        echo "Expected exactly one $build_type/$abi APK" >&2
        exit 1
    fi
    cp "${candidates[0]}" "$destination"
}

copy_tests() {
    local build_type="$1" destination="$2"
    local candidates=(app/build/outputs/apk/androidTest/"$build_type"/*.apk)
    if [ "${#candidates[@]}" -ne 1 ] || [ ! -f "${candidates[0]}" ]; then
        echo "Expected exactly one $build_type instrumentation APK" >&2
        exit 1
    fi
    cp "${candidates[0]}" "$destination"
}

build_fixture() {
    local version="$1" test_type="$2"
    shift 2
    # No release signer or actual embedded API credential is needed or used.
    env -u JARVIS_BETA_GEMINI_API_KEY -u JARVIS_SIGNING_STORE_FILE \
        -u JARVIS_SIGNING_STORE_PASSWORD -u JARVIS_SIGNING_KEY_ALIAS -u JARVIS_SIGNING_KEY_PASSWORD \
        JARVIS_VERSION_CODE="$version" JARVIS_VERSION_NAME="fixture-$version" \
        JARVIS_TEST_BUILD_TYPE="$test_type" bash gradlew --no-daemon "$@"
}

prepare() {
    mkdir -p "$fixtures"/{old,new}/{main,beta} "$fixtures/reports"
    build_fixture 108 updaterCheck assembleUpdaterCheck assembleUpdaterBetaCheck
    copy_apk updaterCheck x86_64 "$fixtures/old/main/app.apk"
    copy_apk updaterBetaCheck x86_64 "$fixtures/old/beta/app.apk"
    build_fixture 109 updaterCheck assembleUpdaterCheck assembleUpdaterBetaCheck assembleUpdaterCheckAndroidTest
    copy_apk updaterCheck x86_64 "$fixtures/new/main/app.apk"
    copy_apk updaterCheck arm64-v8a "$fixtures/new/main/arm64.apk"
    copy_apk updaterBetaCheck x86_64 "$fixtures/new/beta/app.apk"
    copy_apk updaterBetaCheck arm64-v8a "$fixtures/new/beta/arm64.apk"
    copy_tests updaterCheck "$fixtures/new/main/tests.apk"
    build_fixture 109 updaterBetaCheck assembleUpdaterBetaCheckAndroidTest
    copy_tests updaterBetaCheck "$fixtures/new/beta/tests.apk"

    local sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    local signing_tools=("$sdk_root"/build-tools/*/apksigner)
    local apksigner="${signing_tools[${#signing_tools[@]}-1]}"
    if [ -z "$sdk_root" ] || [ ! -x "$apksigner" ]; then
        echo "Android SDK apksigner is required for the wrong-certificate fixture" >&2
        exit 1
    fi
    rm -f "$fixtures/wrong-signing.p12"
    keytool -genkeypair -keystore "$fixtures/wrong-signing.p12" -storetype PKCS12 \
        -alias updater-fixture -keyalg RSA -keysize 2048 -validity 2 \
        -dname "CN=Disposable updater regression" -storepass fixture-only -keypass fixture-only >/dev/null 2>&1
    for variant in main beta; do
        "$apksigner" sign --ks "$fixtures/wrong-signing.p12" --ks-key-alias updater-fixture \
            --ks-pass pass:fixture-only --key-pass pass:fixture-only \
            --out "$fixtures/new/$variant/wrong-signer.apk" "$fixtures/new/$variant/app.apk"
        "$apksigner" verify "$fixtures/new/$variant/wrong-signer.apk"
    done
    rm -f "$fixtures/wrong-signing.p12"
    echo "Prepared debug-signed v108/v109 main and Beta fixtures; no production compatibility is implied."
}

push_fixture() {
    local package="$1" name="$2" source="$3"
    test -f "$source"
    # updater fixtures are debuggable; place archives inside the app sandbox.
    adb shell -T "run-as $package sh -c 'cat > files/updater-regression/$name.apk'" < "$source"
}

instrument() {
    local package="$1" selection="$2" expected="$3" report="$4"
    local output="$fixtures/reports/$report.txt"
    timeout 240s adb shell am instrument -w -r -e class "$selection" \
        "$package.test/androidx.test.runner.AndroidJUnitRunner" > "$output" 2>&1
    cat "$output"
    # am instrument can report a test failure while returning exit zero.
    if ! grep -Eq "OK \\($expected tests?\\)" "$output"; then
        echo "Instrumentation did not report the expected passing test count: $report" >&2
        exit 1
    fi
}

run() {
    if [ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]; then
        echo "Run only on a disposable Android emulator: these tests uninstall fixture package IDs." >&2
        exit 1
    fi
    mkdir -p "$fixtures/reports"
    local retention="com.assistant.core.UpdaterDataRetentionTest"
    local archive="com.assistant.core.UpdaterArchiveVerificationTest"
    local old_archive_tests=""
    for method in matchingSignedPackageIsAccepted oppositeVariantIsRejected nonIncreasingArchiveIsRejected \
        wrongNativeAbiIsRejected mismatchedSigningCertificateIsRejected modifiedDownloadIsDiscardedAndRetrySucceeds \
        missingCachedDownloadIsRecoverable interruptedRecheckKeepsCacheAndRetrySucceeds anotherDistributionSourceIsRejected; do
        old_archive_tests+="${old_archive_tests:+,}$archive#$method"
    done
    for variant in main beta; do
        local package="com.assistant.core" opposite="beta"
        if [ "$variant" = beta ]; then package+=".beta"; opposite="main"; fi
        adb uninstall "$package.test" >/dev/null 2>&1 || true
        adb uninstall "$package" >/dev/null 2>&1 || true
        adb install -r -t "$fixtures/old/$variant/app.apk"
        adb install -r -t "$fixtures/new/$variant/tests.apk"
        instrument "$package" "$retention#seedOlderInstallation" 1 "$variant-seed-v108"
        adb shell run-as "$package" mkdir -p files/updater-regression
        push_fixture "$package" matching "$fixtures/new/$variant/app.apk"
        push_fixture "$package" older "$fixtures/old/$variant/app.apk"
        push_fixture "$package" opposite "$fixtures/new/$opposite/app.apk"
        push_fixture "$package" wrong-abi "$fixtures/new/$variant/arm64.apk"
        push_fixture "$package" wrong-signer "$fixtures/new/$variant/wrong-signer.apk"
        instrument "$package" "$old_archive_tests" 9 "$variant-archive-rejections-v108"
    done

    adb install -r -t "$fixtures/new/main/app.apk"
    instrument com.assistant.core \
        "$retention#verifyRetainedAfterUpgrade,$archive#alreadyInstalledCandidateIsRejectedAfterUpgrade" \
        2 main-upgrade-v109
    instrument com.assistant.core.beta "$retention#verifyOlderInstallationStillRetained" 1 beta-unmodified-after-main-upgrade
    adb install -r -t "$fixtures/new/beta/app.apk"
    instrument com.assistant.core.beta \
        "$retention#verifyRetainedAfterUpgrade,$archive#alreadyInstalledCandidateIsRejectedAfterUpgrade" \
        2 beta-upgrade-v109
    instrument com.assistant.core "$retention#verifyRetainedAfterUpgrade" 1 main-unmodified-after-beta-upgrade
    echo "Both simulated same-signer upgrades retained preferences, voice, chat and encrypted user keys."
}

case "${1:-}" in
    --prepare) prepare ;;
    --run) run ;;
    *) echo "Usage: bash scripts/updater_android_regression.sh --prepare|--run" >&2; exit 2 ;;
esac
