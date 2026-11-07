#!/usr/bin/env bash
# Build one signed three-ABI Personal release, with native llama.cpp only on arm64.
# Caller MUST provide original permanent Personal signing credentials and source checkout.
set -euo pipefail

: "${JARVIS_SIGNING_STORE_FILE:?Permanent signing key path required}"
: "${JARVIS_SIGNING_STORE_PASSWORD:?Permanent signing store password required}"
: "${JARVIS_SIGNING_KEY_ALIAS:?Permanent signing alias required}"
: "${JARVIS_SIGNING_KEY_PASSWORD:?Permanent signing key password required}"
test -s "$JARVIS_SIGNING_STORE_FILE"
test -f app/third_party/llama.cpp/CMakeLists.txt
test -n "${JARVIS_VERSION_CODE:?Version code required}"
test -n "${JARVIS_VERSION_NAME:?Version name required}"
chmod +x gradlew

# These two Gradle builds intentionally use the same source SHA, version, package
# and permanent signing certificate. Only ARM64 receives embedded LFM3.
./gradlew --no-daemon :app:assembleRelease
mkdir -p "$RUNNER_TEMP/jarvis-personal-standard-abis"
for abi in arm64-v8a armeabi-v7a x86_64; do
  from="app/build/outputs/apk/release/app-${abi}-release.apk"
  test -s "$from"
  cp "$from" "$RUNNER_TEMP/jarvis-personal-standard-abis/"
done

./gradlew --no-daemon -PjarvisEmbeddedLfm3=true :app:assembleRelease
arm="app/build/outputs/apk/release/app-arm64-v8a-release.apk"
test -s "$arm"

# Keep legacy 32-bit / desktop emulator APKs but never claim native LFM3 on them.
for abi in armeabi-v7a x86_64; do
  cp "$RUNNER_TEMP/jarvis-personal-standard-abis/app-${abi}-release.apk" \
    "app/build/outputs/apk/release/app-${abi}-release.apk"
done

python3 scripts/verify_personal_embedded_lfm3.py app/build/outputs/apk/release \
  --baseline artifacts/app-debug.apk \
  --version-code "$JARVIS_VERSION_CODE" --version-name "$JARVIS_VERSION_NAME"
echo "Three permanently signed Personal APKs verified, ARM64 LFM3 native library required."
