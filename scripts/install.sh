#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-adb}"
APK="${APK:-$ROOT/dist/HUSH-TV-1.10.0.apk}"

"$ADB" install -r "$APK"
"$ADB" shell cmd role add-role-holder android.app.role.HOME com.hush.tv >/dev/null 2>&1 || true

if [[ "${DISABLE_STOCK_LAUNCHER:-0}" == "1" ]]; then
    # Some TV boxes keep their privileged launcher on top even after the HOME
    # role is assigned. Disabling it makes HUSH the only home app.
    "$ADB" shell pm disable-user --user 0 com.google.android.tvlauncher >/dev/null 2>&1 || true
    "$ADB" shell input keyevent KEYCODE_HOME
    echo "Stock launcher disabled; HOME should now open HUSH."
else
    # Open the app directly so its setup dialog is visible even if the stock
    # launcher still intercepts the HOME key.
    "$ADB" shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
        -n com.hush.tv/.MainActivity >/dev/null 2>&1 || true
    echo "HUSH opened. Follow its setup dialog."
    echo "If HOME still goes to the old launcher, re-run with DISABLE_STOCK_LAUNCHER=1"
    echo "(or use: adb shell pm disable-user --user 0 <stock-launcher-package>)."
fi
