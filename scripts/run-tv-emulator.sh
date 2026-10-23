#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to your Android SDK directory}"
AVD_NAME="${AVD_NAME:-HushTV}"

if [[ "${HEADLESS:-0}" == "1" ]]; then
    exec "$ANDROID_SDK_ROOT/emulator/emulator" "@$AVD_NAME" -no-window
fi

if command -v android >/dev/null 2>&1; then
    # The newer Android CLI returns when the emulator is fully started.
    exec android --sdk="$ANDROID_SDK_ROOT" emulator start "$AVD_NAME"
fi

exec "$ANDROID_SDK_ROOT/emulator/emulator" "@$AVD_NAME"
