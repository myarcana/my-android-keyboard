# Working on this repo from the Linux dev box

`README.md` documents the macOS setup, which is the reference toolchain. This file covers the
Linux dev box (`vmi3516846` on the tailnet) and deploying from it to the phone, because none of
it is discoverable from the repo alone and most of it was learned by hitting the failure first.

Everything here is about *environment*, not design. For what the code does and why, read
`README.md`, `docs/PLAN.md`, and `NOTES.md`.

## The short version

```sh
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=$PWD/../android-sdk ANDROID_SDK_ROOT=$ANDROID_HOME
export GRADLE_USER_HOME=$PWD/../.gradle-home
export HOME=$PWD/..                 # so the debug keystore in ../.android is the one used

./gradlew assembleDebug
tools/adb_tailnet.sh connect        # phone on the fixed port, no arguments
DEVICE=100.121.46.71:5555 tools/deploy.sh
```

`tools/deploy.sh` discovers most of this on its own; the explicit form is what to fall back to
when something looks wrong.

## Why the environment is laid out this way

Four things about this box differ from a laptop, and each one fails in a way that does not name
its own cause.

**The SDK is read-only.** `/opt/android-sdk-min` has API 34 only, and the project needs 36.
`sdkmanager` cannot install into a read-only root, and its own downloader fails here anyway
(manifest fetches time out even though `dl.google.com` is reachable with curl). The workaround is
a *composite* SDK at `../android-sdk`: symlinks to the read-only `platform-tools`,
`cmdline-tools` and `licenses`, plus real directories for `platforms/android-36` and
`build-tools/36.0.0` unpacked by hand from
`https://dl.google.com/android/repository/{platform-36_r02.zip,build-tools_r36_linux.zip}`.
Get the exact filenames from `repository2-3.xml` rather than guessing -- they use underscores,
and the hyphenated guesses 404.

**Gradle and the keystore must live inside the workspace.** The sandbox denies writes to
`/root/.gradle` and `/root/.android`, which surfaces as `Could not create parent directory for
lock file` and `Unable to create debug keystore in /root/.android because it is not writable`.
Hence `GRADLE_USER_HOME` and `HOME` pointing at the workspace.

**The JDK is 17, not the pinned 21.** It builds, but the Mac remains the reference. AGP still
does not support JDK 26 -- that constraint is real, only its enforcement moved: the hardcoded
`org.gradle.java.home=/opt/homebrew/...` was removed from `gradle.properties` because an absolute
path valid on one machine breaks every other one. Select the JDK per machine via `JAVA_HOME` or
a per-user `~/.gradle/gradle.properties`.

**Two runtimes are fetched, not committed, and the app will not *compile* without them.**
`FutoSwipe.kt` hard-imports `org.futo.ml.inference.SwipeDecoder` and `Dictation.kt` imports the
sherpa-onnx bindings, so a checkout missing them fails with a wall of unresolved references. The
graceful degradation the docs describe is a *runtime* fallback, not a compile-time one. The fetch
scripts are `#!/bin/zsh` and there is no zsh here; their download steps work fine under bash if
translated. What matters is the end state:

    third_party/sherpa-onnx/{kotlin-api,jniLibs}/
    third_party/swipe-library/{kotlin-api,jniLibs}/
    app/src/main/assets/asr/{silero_vad.onnx,sensevoice/{model.int8.onnx,tokens.txt}}

Note `swipe-library` here is a partial checkout: it has the C++ core and prebuilt
`build-android-*/libswipe_jni.so`, but not the `android/` module, so `SwipeDecoder.kt` has to
come from upstream at the pinned commit `1b13f2c`.

## Signing: use the shared debug keystore

The phone already has both apps installed, signed with the maintainer's debug key. Installing an
APK signed with a *different* debug key fails with:

    INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package signatures do not match

Do not "fix" this by generating a new keystore, and do not uninstall the apps to get around it --
uninstalling discards on-device state, including anything in the gesture bank that has not been
pulled off the phone yet. Use the existing keystore at `../.android/debug.keystore`
(SHA1 `05:39:C7:C2:30:2F:43:77:8C:FC:D5:64:77:CF:8E:84:4D:31:15:46`). It is the same key as the
Mac's `~/.android/debug.keystore`; that is what makes in-place updates work from either machine.

## Talking to the phone

The phone (`oppo-find-x9-pro`, `100.121.46.71`) is on the tailnet, so it is reachable from
anywhere -- no shared LAN, no USB. What makes it awkward is Android's Wireless debugging, which
issues a **random port** and disarms whenever Wi-Fi changes or the screen locks. Chasing that
port is the thing to avoid, and `tools/adb_tailnet.sh persist` is how: it runs `adb tcpip 5555`
once, moving adbd to a fixed port that is not tied to the toggle. Verified: 5555 stays open
across a full Wi-Fi off/on cycle, and while Wi-Fi is off entirely.

- **Pairing is permanent.** It survives reboots; a pairing code is only needed if the phone
  forgets this machine. Do not ask for one reflexively when a connection fails.
- **The fixed port does not survive a reboot.** adbd reverts to USB-only. Making it permanent
  needs `persist.adb.tcp.port`, which is root-only, and this is a `user` build with no `su`.
  After a reboot: enable Wireless debugging once, read the port, run `persist` again.
- **Pairing port ≠ debugging port.** The pairing port lives in the pop-up next to the 6-digit
  code and closes once pairing completes; the debugging port is on the main screen under
  "IP address & Port". Connecting to the pairing port yields a device stuck in `offline`.
- **"Paired devices" on the phone is not a live connection.** This box stays listed there
  forever. Check `adb devices` instead of trusting that screen.

## Expect the link to be slow and to drop

There is no direct path to the phone -- it is behind carrier CGNAT, so traffic goes through a
DERP relay at ~300-750 ms and roughly 170 KB/s. Consequences worth planning around:

- The keyboard APK is ~320 MB (229 MB of it the SenseVoice model) and takes **about half an
  hour** to install. Run it as a background job; a foreground timeout will kill it mid-transfer.
  The test pad is 6.5 MB and installs in ~25 s, which makes it the right thing to push when
  verifying that the connection and signing are working before committing to the big one.
- `adb` drops the connection during long transfers and between invocations. Reconnect in a
  retry loop, and do a sequence of device commands in **one** shell rather than one per command.
- For rapid iteration, USB from the Mac is dramatically faster and has none of this.

## Enabled subtypes are device state, and reinstalling does not fix them

Installing the APK declares the three subtypes in `method.xml`; it does not *enable* them. Which
ones are active lives in `Settings.Secure.enabled_input_methods`, as hashes appended to each IME
id after a semicolon. A freshly enabled keyboard often gets only one, and the symptom is two
different bugs that look unrelated: the globe key's hold menu is missing languages, and a *tap*
on the globe jumps straight to another keyboard, because the system finds no next subtype within
this IME and looks outside it.

Read the current state, and note that ours had no hashes at all while Gboard had three:

```sh
adb -s 100.121.46.71:5555 shell "settings get secure enabled_input_methods"
adb -s 100.121.46.71:5555 shell "dumpsys input_method | grep -E 'rank=[0-9]+ item='"
```

The `rank=` lines are the authoritative switching list -- that is what the globe walks. The
subtype hashes are stable per declaration and visible as `mSubtypeHashCode` in the same dumpsys
output. For this app they are `-104537768` (en_US), `2045602146` (zh_TW), `-139230659` (zh_CN):

```sh
adb -s 100.121.46.71:5555 shell "settings put secure enabled_input_methods \
  'com.offlinekeyboard.ime/.KeyboardService;-104537768;2045602146;-139230659:<other IMEs unchanged>'"
```

Preserve the other IMEs verbatim; the setting is the whole list, not a patch. The hashes change
if a subtype's declared attributes change, so re-read them from dumpsys after editing
`method.xml` rather than reusing the values above.

## Verifying a deploy actually worked

`pm list packages` only proves an install happened at some point -- it was misleading once
already. Check the timestamp, and look at the screen:

```sh
adb -s 100.121.46.71:5555 shell "dumpsys package com.offlinekeyboard.ime | grep lastUpdateTime"
adb -s 100.121.46.71:5555 shell screencap -p /sdcard/kb.png
adb -s 100.121.46.71:5555 pull /sdcard/kb.png keyboard.png
```

Pull the file rather than piping `exec-out screencap`, which corrupts the PNG here. An all-black
screenshot means the screen is asleep, not that the keyboard crashed: `input keyevent
KEYCODE_WAKEUP`, then `wm dismiss-keyguard`, then re-capture.

## Repo hygiene

The working tree often carries unrelated modifications (`lexicon_en.tsv`, `build_lexicon.py`,
`SpatialModelTest.kt` have all shown up mid-session). Check `git status` before committing and
stage only what you touched.

Build outputs, the composite SDK, `../.gradle-home`, `../.android` and the fetched runtimes are
all outside the repo or git-ignored; none of them should ever be committed.
