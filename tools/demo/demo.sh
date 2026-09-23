#!/bin/zsh
# Product demo videos of the keyboard, recorded in an emulator and typed by a script.
#
#   tools/demo/demo.sh list                 the scenarios that exist
#   tools/demo/demo.sh boot                 start the emulator and leave it running
#   tools/demo/demo.sh make glide           record and render one scenario
#   tools/demo/demo.sh record glide         record only, leaving the raw capture and the log
#   tools/demo/demo.sh render glide         render again from what was already recorded
#   tools/demo/demo.sh install              rebuild and reinstall all four APKs
#   tools/demo/demo.sh shutdown             stop the emulator
#
# Output lands in build/demo/<scenario>/: raw.mp4 straight off the device, and <scenario>.mp4
# with the finger circles, the device frame and the captions on it.
#
# The pipeline, and why it has the shape it has:
#
#   1. DemoGeometry (instrumentation on the keyboard) measures where every key is. This kills the
#      keyboard's process on the way out -- `am instrument` always does -- which is why it runs
#      before anything is recorded and why the keyboard is raised again afterwards.
#   2. plan.py turns the scenario into pointer samples with millisecond timings.
#   3. screenrecord starts.
#   4. DemoPlayer (instrumentation on the test pad) injects the samples.
#   5. render.py finds the sync tap in the video, and draws.
#
# The emulator is a Pixel 6 (1080x2400, 420 dpi) called demo_pixel. `setup` creates it if it is
# missing; see the skill in .claude/skills/demo-video/ for what it costs on disk.

set -e

export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
export ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
AVD=${AVD:-demo_pixel}
SYSTEM_IMAGE="system-images;android-36;google_apis;arm64-v8a"

IME=com.offlinekeyboard.ime
PAD=com.offlinekeyboard.testpad
DRIVER=com.offlinekeyboard.demodriver
HERE=${0:a:h}
ROOT=${HERE:h:h}
OUT=$ROOT/build/demo
VENV=$HERE/.venv

# The device is always the emulator, never a phone plugged in next to it. A demo recorded on the
# phone would be fine; a demo recorded on whichever device happened to answer first would not.
adb() { "$ADB" -s "${DEVICE:-emulator-5554}" "$@" }

python() {
    [[ -x $VENV/bin/python ]] || {
        echo "-- creating $VENV (numpy, for the renderer)" >&2
        python3 -m venv $VENV
        $VENV/bin/pip install -q numpy pillow
    }
    $VENV/bin/python "$@"
}

## ---------------------------------------------------------------- the emulator

setup() {
    if [[ ! -d ~/.android/avd/$AVD.avd ]]; then
        echo "-- creating the $AVD virtual device"
        yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "emulator" "$SYSTEM_IMAGE" >/dev/null
        echo no | $ANDROID_HOME/cmdline-tools/latest/bin/avdmanager \
            create avd -n $AVD -k "$SYSTEM_IMAGE" -d pixel_6 >/dev/null 2>&1 || true
        # avdmanager writes a config with placeholders where the device profile should have gone
        # (it cannot read devices.xml out of a command-line-tools SDK), so the parts that matter
        # are set here: a 2 GB data partition rather than 10, and no hardware keyboard, which
        # otherwise suppresses the on-screen one entirely.
        local config=~/.android/avd/$AVD.avd/config.ini
        sed -i '' \
            -e "s/^avd.name=.*/avd.name=$AVD/" -e "s/^avd.id=.*/avd.id=$AVD/" \
            -e '/^disk.dataPartition.path=/d' \
            -e 's/^disk.dataPartition.size=.*/disk.dataPartition.size=2G/' \
            -e 's/^sdcard.size=.*/sdcard.size=128 MB/' \
            -e 's/^hw.ramSize=.*/hw.ramSize=3072/' \
            -e 's/^hw.keyboard=.*/hw.keyboard=no/' \
            -e 's/^hw.gpu.enabled=.*/hw.gpu.enabled=yes/' \
            -e 's/^hw.gpu.mode=.*/hw.gpu.mode=host/' \
            $config
    fi
}

boot() {
    setup
    if adb get-state >/dev/null 2>&1; then
        echo "-- emulator already running"
    else
        echo "-- booting $AVD"
        # Headless, on the host GPU, and both halves of that matter for the capture:
        #
        #   - With a window, macOS throttles the emulator's GL context the moment the window is
        #     not frontmost. The first take recorded 59 frames in 44 seconds -- roughly one per
        #     second -- because the terminal was in front of it.
        #   - `-gpu swiftshader_indirect` avoids that by rendering in software, and manages about
        #     10 fps, which is not a demo video either.
        #
        # Headless with `-gpu host` renders offscreen at ~55 fps and nothing can occlude it. To
        # watch a take as it happens, drop -no-window and keep the window in front.
        #
        # -no-snapshot-save: every take starts from the same state, and nothing accumulates on
        # disk between them. Booting costs about 30 seconds.
        nohup $EMULATOR -avd $AVD -no-window -no-boot-anim -no-snapshot-save -gpu host \
            > $OUT/emulator.log 2>&1 &
        adb wait-for-device
        while [[ $(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') != 1 ]]; do
            sleep 2
        done
    fi
    adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
    # The on-screen keyboard is suppressed while the emulator claims a hardware one.
    adb shell settings put secure show_ime_with_hard_keyboard 1
    stage_dressing
    ensure_installed
    adb shell ime enable $IME/.KeyboardService >/dev/null
    adb shell ime set $IME/.KeyboardService >/dev/null
}

# A clean status bar: fixed clock, full battery, no notification icons. Otherwise the shot is
# dated by a clock that reads 04:09 and decorated with whatever the system felt like saying.
stage_dressing() {
    adb shell settings put global sysui_demo_allowed 1
    local demo="am broadcast -a com.android.systemui.demo"
    adb shell $demo -e command enter >/dev/null
    adb shell $demo -e command clock -e hhmm 0941 >/dev/null
    adb shell $demo -e command battery -e level 100 -e plugged false >/dev/null
    adb shell $demo -e command network -e wifi show -e level 4 -e mobile hide >/dev/null
    adb shell $demo -e command notifications -e visible false >/dev/null
}

shutdown() {
    adb emu kill >/dev/null 2>&1 || true
    echo "-- emulator stopped"
}

## ---------------------------------------------------------------- the APKs

build() {
    echo "-- building"
    (cd $ROOT && ./gradlew -q \
        :app:assembleDebug :app:assembleDebugAndroidTest \
        :testpad:assembleDebug \
        :demodriver:assembleDebug :demodriver:assembleDebugAndroidTest)
}

install() {
    build
    echo "-- installing"
    adb install -r $ROOT/app/build/outputs/apk/debug/app-debug.apk >/dev/null
    adb install -r $ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
    adb install -r $ROOT/testpad/build/outputs/apk/debug/testpad-debug.apk >/dev/null
    adb install -r $ROOT/demodriver/build/outputs/apk/debug/demodriver-debug.apk >/dev/null
    adb install -r $ROOT/demodriver/build/outputs/apk/androidTest/debug/demodriver-debug-androidTest.apk >/dev/null
}

ensure_installed() {
    local installed=$(adb shell pm list packages | tr -d '\r')
    if [[ $installed != *$IME.test* || $installed != *$DRIVER.test* || $installed != *$PAD* ]]; then
        install
    fi
}

## ---------------------------------------------------------------- a take

# Files move in and out of the two apps' private directories with run-as rather than through
# /sdcard: an app may not read /data/local/tmp under SELinux, and shared storage would mean this
# keyboard declaring a storage permission it has no business holding.
push_to() { adb shell "run-as $1 sh -c 'mkdir -p files/demo; cat > files/demo/$2'" < $3 }
pull_from() { adb shell "run-as $1 cat files/demo/$2" > $3 }

# Instrumentation, with its output kept. Grepping the stream directly went wrong in a way worth
# remembering: the recorder runs as a background job writing to the same terminal, and its output
# interleaved with the runner's, so the "OK (1 test)" line stopped starting a line and every take
# reported a failure that had not happened.
instrument() {
    local log=$1 target=$2 class=$3
    shift 3
    adb shell am instrument -w -e class $class "$@" \
        $target/androidx.test.runner.AndroidJUnitRunner > $log 2>&1
    if ! grep -qE "^OK" $log; then
        echo "   ${class%%#*} failed:"
        sed -n '1,25p' $log | sed 's/^/     /'
        return 1
    fi
}

# Where every key is, measured once per screen and kept.
#
# Retried, and cached, because raising the keyboard from the measuring run is unreliable in a way
# that has not been worth chasing further: roughly every other attempt is dropped by the system
# between the app asking and the keyboard appearing. The second attempt has always worked, and
# the answer only changes when the screen or the layout does -- so it is measured once per screen
# size and reused, and a take does not touch this at all.
measure() {
    local field=$1 take=$2
    local size=$(adb shell wm size | tr -d '\r' | sed 's/.*: //')
    local cache=$OUT/geometry-$size-field$field.json

    if [[ ! -f $cache ]]; then
        echo "-- measuring the keyboard"
        local attempt
        for attempt in 1 2 3 4; do
            if instrument $take/measure.log $IME.test \
                com.offlinekeyboard.ime.demo.DemoGeometry#measure -e field $field 2>/dev/null
            then
                pull_from $IME geometry.json $cache
                break
            fi
            [[ $attempt == 4 ]] && { echo "   could not measure the keyboard"; exit 1 }
        done
    fi
    cp $cache $take/geometry.json
}

# Starts the keyboard's process and leaves it warm.
#
# A take that begins with the keyboard cold loses its first show request to a timeout -- this
# keyboard carries its models with it and takes seconds to start. Paying that here, before
# anything is being recorded or measured, is what keeps takes reproducible.
warm() {
    local field=${1:-0} attempt waited
    for attempt in 1 2 3; do
        # A cold start of the test pad, every time: the activity raises the keyboard from
        # `onCreate` (see `demoField` in TestPadActivity), and a warm relaunch delivers the intent
        # to the running instance instead, where the same request is quietly dropped.
        adb shell am force-stop $PAD
        sleep 1
        adb shell am start -n $PAD/.TestPadActivity --ei demoField $field >/dev/null
        for waited in $(seq 25); do
            [[ $(adb shell dumpsys input_method | grep -c "mInputShown=true") == 1 ]] && {
                # Up. Give it a moment to finish sliding and to stop being a cold process.
                sleep 3
                return
            }
            sleep 1
        done
        echo "   the keyboard did not come up; retrying"
    done
    echo "   giving up on the keyboard"
    exit 1
}

record() {
    local name=$1
    local scenario=$HERE/scenarios/$name.json
    [[ -f $scenario ]] || { echo "no scenario $scenario"; exit 1 }
    local take=$OUT/$name
    mkdir -p $take

    boot >/dev/null
    stage_dressing >/dev/null

    local field=$(python -c "import json,sys;print(json.load(open('$scenario')).get('field',0))")
    measure $field $take

    echo "-- planning"
    python $HERE/plan.py $scenario $take/geometry.json $take/plan.json
    push_to $DRIVER plan.json $take/plan.json

    # Warmed *after* measuring, which is the order that matters: measuring is instrumentation on
    # the keyboard, so it ends by killing the keyboard's process. A take that followed it
    # immediately spent its first four seconds typing into a keyboard that was still starting,
    # and those keystrokes went nowhere -- the first recording made this way ended up holding one
    # word out of six.
    warm $field

    # Recording starts first and the player raises the keyboard once it is running. It has to be
    # that way round: `am instrument` kills the target's processes on the way in, so a keyboard
    # raised from here would be gone before the first event was injected. The renderer trims the
    # keyboard's arrival off the front.
    echo "-- recording"
    adb shell screenrecord --bit-rate 24M --time-limit 180 /sdcard/demo-raw.mp4 \
        > $take/screenrecord.log 2>&1 &
    local recorder=$!
    sleep 1.5
    instrument $take/play.log $DRIVER.test com.offlinekeyboard.demodriver.DemoPlayer#play \
        || { adb shell pkill -INT -f screenrecord; exit 1 }
    # SIGINT rather than kill: screenrecord finalises the MP4 on interrupt and produces an
    # unplayable file without it.
    adb shell pkill -INT -f screenrecord || true
    wait $recorder 2>/dev/null || true
    sleep 1.5

    adb pull /sdcard/demo-raw.mp4 $take/raw.mp4 >/dev/null
    pull_from $DRIVER touches.jsonl $take/touches.jsonl
    echo "-- recorded $take/raw.mp4"
}

render() {
    local name=$1
    local take=$OUT/$name
    [[ -f $take/raw.mp4 ]] || { echo "nothing recorded for $name -- run: $0 record $name"; exit 1 }
    python $HERE/render.py $take/raw.mp4 $take/touches.jsonl $take/plan.json \
        $take/geometry.json $take/$name.mp4 "${@:2}"
    echo "-- wrote $take/$name.mp4"
}

## ---------------------------------------------------------------- entry

mkdir -p $OUT
case ${1:-list} in
    list)
        echo "scenarios in ${HERE#$ROOT/}/scenarios:"
        for f in $HERE/scenarios/*.json; do
            printf "  %-14s %s\n" ${${f:t}%.json} \
                "$(python -c "import json;print(json.load(open('$f')).get('title',''))")"
        done
        ;;
    setup) setup ;;
    boot) boot ;;
    shutdown) shutdown ;;
    build) build ;;
    install) boot >/dev/null; install ;;
    record) record ${2:?which scenario} ;;
    render) render ${2:?which scenario} "${@:3}" ;;
    make) record ${2:?which scenario}; render ${2} "${@:3}" ;;
    *) sed -n '2,30p' $0 ;;
esac
