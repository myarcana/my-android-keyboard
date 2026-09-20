#!/usr/bin/env bash
# Incremental deploy: push only what changed.
#
#   tools/deploy_incremental.sh [--pack] [--screenshot out.png]
#
# The keyboard APK is ~320 MB and takes ~30 minutes over a relayed tailnet link, because 300 MB
# of it is the SenseVoice model and the native runtimes -- bytes that were already on the phone
# and did not change. This script splits the payload by how often it changes:
#
#   * the **model pack** (:modelpack) carries the models and the native libraries. Installed once,
#     and again only when `modelpack/build.gradle.kts`'s `versionCode` changes -- i.e. when a
#     model is actually replaced.
#   * the **code APK** (:app, built with `-Ppack`) carries Kotlin, resources and the small
#     assets, and that is what every deploy installs. Single-digit megabytes, seconds to transfer.
#
# Both are signed with the shared debug key, so the pack goes into the keyboard's process and the
# keyboard reads its assets straight out of it (see `com.offlinekeyboard.ime.pack.ModelPack`).
#
# `--pack` forces the pack to be rebuilt and reinstalled, for the first run or after a model
# change. Without it the installed pack's `versionCode` is compared against the module's and the
# pack step is skipped when they agree -- so the common case is one small install.
#
# The size accounting is the point, so the script prints it: the bytes actually pushed, both ways.

set -euo pipefail
cd "$(dirname "$0")/.."
REPO=$PWD

FORCE_PACK=0
SHOT=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --pack) FORCE_PACK=1; shift ;;
        --screenshot) SHOT=${2:?}; shift 2 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

# --- toolchain ----------------------------------------------------------------------------
# Same discovery order as tools/deploy.sh, kept in step deliberately: this script is the fast
# path and has to run wherever the slow one does.
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
[[ -n "${JAVA_HOME:-}" ]] || { echo "error: no JDK found; set JAVA_HOME" >&2; exit 1; }
export JAVA_HOME

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
PACK_PKG=com.offlinekeyboard.ime.models

# --- device -------------------------------------------------------------------------------
if [[ -n "${DEVICE:-}" ]]; then
    [[ "$DEVICE" == *:* ]] && "$ADB" connect "$DEVICE" >/dev/null 2>&1 || true
    adb() { "$ADB" -s "$DEVICE" "$@"; }
else
    adb() { "$ADB" "$@"; }
fi
adb shell true >/dev/null 2>&1 || { echo "error: no device; set DEVICE=<host:port>" >&2; exit 1; }

# --- what the pack is expected to be ------------------------------------------------------
# Read out of the module rather than duplicated, so the bump is one edit in one place.
pack_version() {
    sed -n 's/^ *versionCode *= *\([0-9]\+\).*/\1/p' modelpack/build.gradle.kts | head -1
}
WANT_PACK_VERSION=$(pack_version)
[[ -n "$WANT_PACK_VERSION" ]] || { echo "error: could not read modelpack versionCode" >&2; exit 1; }

installed_pack_version() {
    # `pm dump` rather than `dumpsys package`: the latter is enormous and this runs before every
    # deploy. versionCode appears as `versionCode=1 minSdk=29 ...`.
    adb shell "pm dump $PACK_PKG 2>/dev/null | grep -m1 versionCode" 2>/dev/null |
        sed -n 's/.*versionCode=\([0-9]\+\).*/\1/p'
}

INSTALLED_PACK_VERSION=$(installed_pack_version || true)

NEED_PACK=0
if [[ $FORCE_PACK -eq 1 ]]; then
    NEED_PACK=1
elif [[ -z "$INSTALLED_PACK_VERSION" ]]; then
    NEED_PACK=1
elif [[ "$INSTALLED_PACK_VERSION" != "$WANT_PACK_VERSION" ]]; then
    NEED_PACK=1
fi

# --- build --------------------------------------------------------------------------------
# One gradle invocation for both, so configuration and the (large) dependency resolution are
# paid once. `-Ppack` on :app is what drops the models and the `.so`s from the code APK.
echo "building…"
PACK_ARGS=()
[[ $NEED_PACK -eq 1 ]] && PACK_ARGS=(:modelpack:assembleDebug)
./gradlew --quiet "${PACK_ARGS[@]}" :app:assembleDebug -Ppack

APP_APK=app/build/outputs/apk/debug/app-debug.apk
PACK_APK=modelpack/build/outputs/apk/debug/modelpack-debug.apk
[[ -f "$APP_APK" ]] || { echo "error: $APP_APK not built" >&2; exit 1; }

app_bytes=$(stat -c %s "$APP_APK" 2>/dev/null || stat -f %z "$APP_APK")
echo "code APK: $(( app_bytes / 1024 / 1024 )) MB"

# The pack is only *pushed* when it is needed, but it is worth knowing its size either way,
# because that number is the reason this script exists.
pack_bytes=0
if [[ -f "$PACK_APK" ]]; then
    pack_bytes=$(stat -c %s "$PACK_APK" 2>/dev/null || stat -f %z "$PACK_APK")
fi

# --- verifier -----------------------------------------------------------------------------
# ColorOS blocks installs behind a confirmation screen unless the verifier is off; see NOTES.md.
if [[ "$(adb shell settings get global verifier_verify_adb_installs | tr -d '\r')" != "0" ]]; then
    echo "note: package verifier is on -- installs will hang. Disabling it."
    adb shell settings put global verifier_verify_adb_installs 0
    adb shell settings put global package_verifier_user_consent -1
fi

# --- pack first ---------------------------------------------------------------------------
# Order matters: the code APK's Application will look for the pack on its first run, and an
# install of the pack afterwards would be one restart late. Installing the pack first also means
# a failure here leaves the previous keyboard untouched rather than a keyboard with no models.
if [[ $NEED_PACK -eq 1 ]]; then
    echo "installing model pack (once; only changes when a model changes)…"
    t0=$(date +%s)
    adb install -r -d "$PACK_APK"
    echo "  pack installed in $(( $(date +%s) - t0 ))s"
else
    echo "model pack already current (v$INSTALLED_PACK_VERSION); not pushed"
fi

# --- code ---------------------------------------------------------------------------------
echo "installing code APK…"
t0=$(date +%s)
adb install -r "$APP_APK"
code_seconds=$(( $(date +%s) - t0 ))
echo "  code installed in ${code_seconds}s"

# --- make the keyboard the current one ----------------------------------------------------
# Never force-stop the package: the system falls back to another keyboard. Re-selecting the IME
# after the install is what makes the new APK actually run.
adb shell ime enable "$IME" >/dev/null 2>&1 || true
adb shell ime set "$IME" >/dev/null

if [[ -n "$SHOT" ]]; then
    # A screen that has timed out produces an all-black screenshot, which reads as a crash.
    adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
    adb shell am start -S -n com.offlinekeyboard.testpad/.TestPadActivity >/dev/null 2>&1 || true
    sleep 1.5
    adb exec-out screencap -p > "$SHOT"
    echo "screenshot: $SHOT"
fi

# --- report -------------------------------------------------------------------------------
# The one line that answers "did the split actually help": the bytes that crossed the link this
# time, against the bytes a full install would have crossed.
pushed=$app_bytes
[[ $NEED_PACK -eq 1 ]] && pushed=$(( pushed + pack_bytes ))
full=$(( pack_bytes > 0 ? pack_bytes : app_bytes ))
echo
echo "pushed $(( pushed / 1024 / 1024 )) MB in ${code_seconds}s" \
     "(a self-contained install would push the full ~320 MB)"
