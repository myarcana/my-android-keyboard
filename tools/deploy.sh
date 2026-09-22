#!/usr/bin/env bash
# Build, install both apps, select the keyboard, open the test pad, and screenshot it.
#
# Encodes the sequence that is easy to get wrong by hand: never force-stop the package (the
# system falls back to another keyboard), always re-select the IME after installing, and take
# the screenshot late enough to miss the launch animation.
#
# Usage: tools/deploy.sh [output.png]
#
# Runs on macOS and Linux. Toolchain locations are discovered rather than hardcoded, so the
# same script works from the Mac and from a headless Linux box driving the phone over the
# tailnet. Override any of JAVA_HOME / ANDROID_HOME / ADB / DEVICE from the environment.
#
# Deploying to a phone that is not on USB (e.g. over Tailscale) needs a target:
#
#   DEVICE=100.121.46.71:37123 tools/deploy.sh
#
# Android 11+ requires a one-time pairing before that address is connectable, and wireless
# debugging only arms while the phone is associated with a Wi-Fi network -- it is refused on
# cellular alone, no matter how reachable the phone otherwise is. See tools/adb_tailnet.sh.

set -euo pipefail

cd "$(dirname "$0")/.."
REPO=$PWD

# --- java ---------------------------------------------------------------------------------
# AGP does not support JDK 26 (see README); prefer 21, then any JDK that is at least 17.
if [[ -z "${JAVA_HOME:-}" ]]; then
    for candidate in \
        /opt/homebrew/opt/openjdk@21 \
        /usr/lib/jvm/java-21-openjdk-amd64 \
        /usr/lib/jvm/java-17-openjdk-amd64 \
        /opt/homebrew/opt/openjdk@17
    do
        [[ -x "$candidate/bin/javac" ]] && { JAVA_HOME=$candidate; break; }
    done
fi
if [[ -z "${JAVA_HOME:-}" ]] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
    JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
fi
[[ -n "${JAVA_HOME:-}" ]] || { echo "error: no JDK found; set JAVA_HOME" >&2; exit 1; }
export JAVA_HOME

# --- android sdk --------------------------------------------------------------------------
if [[ -z "${ANDROID_HOME:-}" ]]; then
    for candidate in \
        "$REPO/../android-sdk" \
        /opt/homebrew/share/android-commandlinetools \
        "$HOME/Library/Android/sdk" \
        "$HOME/Android/Sdk" \
        /opt/android-sdk-min
    do
        [[ -d "$candidate/platform-tools" ]] && { ANDROID_HOME=$(cd "$candidate" && pwd); break; }
    done
fi
[[ -n "${ANDROID_HOME:-}" ]] || { echo "error: no Android SDK found; set ANDROID_HOME" >&2; exit 1; }
export ANDROID_HOME ANDROID_SDK_ROOT=$ANDROID_HOME

ADB=${ADB:-$ANDROID_HOME/platform-tools/adb}
[[ -x "$ADB" ]] || ADB=$(command -v adb || true)
[[ -n "$ADB" && -x "$ADB" ]] || { echo "error: adb not found; set ADB" >&2; exit 1; }

IME=com.offlinekeyboard.ime/.KeyboardService
SHOT=${1:-/tmp/keyboard.png}

# --- device selection ---------------------------------------------------------------------
# With DEVICE set, talk to exactly that transport; connect first when it looks like host:port,
# because a tailnet device is not attached until adb dials it.
if [[ -n "${DEVICE:-}" ]]; then
    [[ "$DEVICE" == *:* ]] && "$ADB" connect "$DEVICE" >/dev/null 2>&1 || true
    adb() { "$ADB" -s "$DEVICE" "$@"; }
else
    adb() { "$ADB" "$@"; }
fi

if ! adb shell true >/dev/null 2>&1; then
    echo "error: no device. Plug in over USB, or set DEVICE=<host:port> for wireless." >&2
    echo "       Wireless debugging needs the phone on Wi-Fi; see tools/adb_tailnet.sh." >&2
    exit 1
fi

# --- gradle home --------------------------------------------------------------------------
# Gradle writes its wrapper caches under $GRADLE_USER_HOME, default ~/.gradle. On a sandboxed
# box /root is not writable, and the wrapper fails with "Could not create parent directory for
# lock file" -- before any build output, and in a way that says nothing about sandboxing. The
# workspace copy is the one the documented build uses, so prefer it when it exists.
if [[ -z "${GRADLE_USER_HOME:-}" && -d "$REPO/../.gradle-home" ]]; then
    GRADLE_USER_HOME=$(cd "$REPO/../.gradle-home" && pwd)
    export GRADLE_USER_HOME
fi

./gradlew --quiet assembleDebug

# ColorOS blocks installs behind a confirmation screen unless the verifier is off; see NOTES.md.
if [[ "$(adb shell settings get global verifier_verify_adb_installs | tr -d '\r')" != "0" ]]; then
    echo "note: package verifier is on -- installs will hang. Disabling it."
    adb shell settings put global verifier_verify_adb_installs 0
    adb shell settings put global package_verifier_user_consent -1
fi

# A screen that has timed out produces an all-black screenshot, which looks like a crash.
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true   # this phone has no PIN

# Two apps now: the keyboard, and the pad it is typed into. The pad shares no code with the
# keyboard and is installed separately, which is also what makes the -S below safe -- it
# force-stops the pad's package rather than the keyboard's, so the IME selection survives it.
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r testpad/build/outputs/apk/debug/testpad-debug.apk >/dev/null
adb shell ime enable "$IME" >/dev/null 2>&1 || true
adb shell ime set "$IME" >/dev/null
# -S forces a fresh activity instance. Without it am start merely resumes the existing one,
# so onCreate never runs and the pad keeps whatever text and caret position it had.
adb shell am start -S -n com.offlinekeyboard.testpad/.TestPadActivity >/dev/null

sleep 1.5   # let the window animation settle before capturing
adb exec-out screencap -p > "$SHOT"
echo "screenshot: $SHOT"
