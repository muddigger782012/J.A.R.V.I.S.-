#!/usr/bin/env bash
set -euo pipefail

# Only a build of the current main source may advance the normal updater channel.
if [ "$GITHUB_REF" != refs/heads/main ]; then
    echo "Signed candidate built outside main; updater publication skipped."
    exit 0
fi
git fetch origin main
if [ "$(git rev-parse origin/main)" != "$GITHUB_SHA" ]; then
    echo "Main changed after this build began; updater publication skipped."
    exit 0
fi

APK="$(find app/build/outputs/apk/release -maxdepth 1 -name '*arm64-v8a*.apk' -print -quit)"
test -n "$APK"
cp "$APK" "$RUNNER_TEMP/jarvis-arm64-latest.apk"
# git reset must not discard the exact plan embedded in this APK.
cmp docs/intelligence-plan.json app/src/main/assets/intelligence-plan.json
cp docs/intelligence-plan.json "$RUNNER_TEMP/jarvis-built-plan.json"
git reset --hard origin/main
mkdir -p artifacts
cp "$RUNNER_TEMP/jarvis-arm64-latest.apk" artifacts/app-debug.apk
cp "$RUNNER_TEMP/jarvis-built-plan.json" docs/intelligence-plan.json
cp "$RUNNER_TEMP/jarvis-built-plan.json" app/src/main/assets/intelligence-plan.json
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git add artifacts/app-debug.apk docs/intelligence-plan.json app/src/main/assets/intelligence-plan.json
if git diff --cached --quiet; then
    echo "Updater APK and public plan are already current."
else
    git commit -m "Publish JARVIS updater APK [skip ci]"
    # A concurrent source push causes a normal non-fast-forward rejection.
    git push origin HEAD:main
fi
