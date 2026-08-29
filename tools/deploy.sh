#!/bin/zsh
# Build, install, select the keyboard, open the test pad, and screenshot it.
#
# Encodes the sequence that is easy to get wrong by hand: never force-stop the package (the
# system falls back to another keyboard), always re-select the IME after installing, and take
# the screenshot late enough to miss the launch animation.
#
# Usage: tools/deploy.sh [output.png]

set -e

export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
export ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
ADB="$ANDROID_HOME/platform-tools/adb"
IME=com.offlinekeyboard.ime/.KeyboardService
SHOT=${1:-/tmp/keyboard.png}

cd "$(dirname "$0")/.."

./gradlew --quiet assembleDebug

# ColorOS blocks installs behind a confirmation screen unless the verifier is off; see NOTES.md.
if [[ "$($ADB shell settings get global verifier_verify_adb_installs | tr -d '\r')" != "0" ]]; then
    echo "note: package verifier is on -- installs will hang. Disabling it."
    $ADB shell settings put global verifier_verify_adb_installs 0
    $ADB shell settings put global package_verifier_user_consent -1
fi

# A screen that has timed out produces an all-black screenshot, which looks like a crash.
$ADB shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
$ADB shell wm dismiss-keyguard >/dev/null 2>&1 || true   # this phone has no PIN

$ADB install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
# -S forces a fresh activity instance. Without it am start merely resumes the existing one,
# so onCreate never runs and the pad keeps whatever text and caret position it had.
# It force-stops the package, so re-select the IME afterwards.
$ADB shell am start -S -n com.offlinekeyboard.ime/.TestPadActivity >/dev/null
$ADB shell ime enable "$IME" >/dev/null 2>&1 || true
$ADB shell ime set "$IME" >/dev/null

sleep 1.5   # let the window animation settle before capturing
$ADB exec-out screencap -p > "$SHOT"
echo "screenshot: $SHOT"
