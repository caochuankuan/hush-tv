#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to your Android SDK directory}"
AVD_NAME="${AVD_NAME:-HushTV}"
TV_IMAGE="${TV_IMAGE:-system-images;android-34;android-tv;arm64-v8a}"
SDKMANAGER="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/avdmanager"

if [[ ! -d "$ANDROID_SDK_ROOT/system-images/android-34/android-tv/arm64-v8a" ]]; then
    echo "Installing $TV_IMAGE ..."
    yes | "$SDKMANAGER" "$TV_IMAGE"
fi

echo no | "$AVDMANAGER" create avd \
    -n "$AVD_NAME" \
    -k "$TV_IMAGE" \
    -d tv_1080p \
    --force

echo "Created AVD $AVD_NAME (1920x1080 / Android TV)."
echo "Start it with: $ANDROID_SDK_ROOT/emulator/emulator @$AVD_NAME"
