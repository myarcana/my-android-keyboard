# Development notes

Hard-won gotchas, so nobody has to rediscover them. Append as you learn things; keep the
newest sections at the top of each category.

---

## Device: OPPO CPH2791, Android 16 (ColorOS)

### `adb install` hangs forever — bypass the install confirmation

**Symptom.** `adb install -r app-debug.apk` prints `Performing Streamed Install` and then never
returns. The APK actually installs fine; a full-screen ColorOS confirmation appears on the
phone showing the app with **Open / Close** buttons, and adb blocks until it is dismissed by
hand. Every single install.

**Fix.** Disable the package verifier once, over adb:

```sh
adb shell settings put global verifier_verify_adb_installs 0
adb shell settings put global package_verifier_enable 0
adb shell settings put global package_verifier_user_consent -1
adb shell settings put secure install_non_market_apps 1
```

Installs then complete in a couple of seconds with no prompt. These persist across reboots
but may be reset by a system update, so re-apply if the hang comes back. To restore the
stock behaviour, set `verifier_verify_adb_installs` and `package_verifier_user_consent` back
to `1`.

Check the current state with `adb shell settings get global verifier_verify_adb_installs`.

**If it hangs anyway**, the install has usually already succeeded. Confirm with
`adb shell dumpsys package com.offlinekeyboard.ime | grep lastUpdateTime`, then dismiss the
dialog with `adb shell input keyevent KEYCODE_BACK`.

### `am start` resumes, it does not restart

Launching an activity that is already on top merely resumes it: `onCreate` never runs, so
changes to initial state (text, caret position) appear not to have taken effect even though
the new APK is installed. Pass `-S` to force a fresh instance. That force-stops the package,
so re-select the IME afterwards. `tools/deploy.sh` does both, in that order.

### Never `am force-stop` the keyboard's own package

Force-stopping `com.offlinekeyboard.ime` kills the running IME, and Android immediately falls
back to another installed keyboard (Gboard here). The next screenshot then shows *Gboard*,
which is easy to mistake for "my layout changes did nothing". Reinstalling alone does not
switch back — re-select it explicitly:

```sh
adb shell ime set com.offlinekeyboard.ime/.KeyboardService
```

`adb install -r` replaces the APK without needing a force-stop, so there is no reason to do it.

### The phone must be unlocked for screenshots

A screen that has timed out screenshots as pure black, which looks alarmingly like a crash.
`adb shell input keyevent KEYCODE_WAKEUP` turns the screen on but lands on the lock screen,
and adb cannot get past a PIN. `tools/deploy.sh` sends WAKEUP, but **the phone still has to be
unlocked by hand** for the screenshot to show anything useful. Consider raising the screen
timeout while working: `adb shell settings put system screen_off_timeout 1800000`.

### Multi-touch gestures cannot be scripted

`adb shell input` is single-pointer only, so gestures needing two fingers — notably the
second-finger tap that starts selection during spacebar trackpad mode — cannot be driven from
adb. They are covered by JVM unit tests against the state machine; on-device confirmation has
to be done by hand.

### Screenshots catch the launch animation

`adb exec-out screencap -p` immediately after `am start` usually captures the window
transition, not the keyboard — the keyboard appears part-way up the screen with rows missing.
Take a second screenshot; do not conclude the layout is broken from the first one.

### Other adb hangs on this device

- `adb shell ime enable <id>` hung indefinitely once. Enabling the keyboard through
  Settings -> Manage keyboards works reliably; prefer that.
- Launching an app with a guessed component name (`am start -n com.coloros.note/.MainActivity`)
  hangs rather than erroring. Verify the component exists first with
  `adb shell cmd package resolve-activity --brief <package>`.
- A hanging adb command is not evidence the device is gone. Probe with `adb shell echo alive`
  before concluding anything.

---

## Android platform

### IME windows are edge-to-edge from targetSdk 35

The bottom key row rendered *underneath* the navigation bar's back/home/recents buttons.
The IME window now spans the full screen, so the input view must reserve the navigation bar
height itself.

`KeyboardView` handles this in `onApplyWindowInsets`, reading
`WindowInsetsCompat.Type.navigationBars().bottom` and adding it to the measured height. The
keys are laid out in the *top* portion and the inset is empty space below, which means draw
coordinates and touch coordinates stay identical (both measured from the top) — no canvas
translation and no touch offset to get wrong.

### Positioning a popup from an IME

An IME cannot draw inside the target app's text field, but it *can* place a `PopupWindow`
anywhere on screen. Two traps when doing that:

- **`CursorAnchorInfo` gives screen coordinates; `showAtLocation` wants window coordinates.**
  For an IME the parent window's origin is the top of the keyboard, so passing screen
  coordinates straight through puts the popup ~1400px too low. Convert explicitly:

  ```kotlin
  val onScreen = IntArray(2); val inWindow = IntArray(2)
  view.getLocationOnScreen(onScreen); view.getLocationInWindow(inWindow)
  val originY = onScreen[1] - inWindow[1]   // subtract from the screen coordinate
  ```

- **`CursorAnchorInfo` arrives asynchronously.** It is not available in the same frame that
  `requestCursorUpdates(CURSOR_UPDATE_MONITOR)` is called, so code must *wait* for it rather
  than treat a missing caret as a reason to tear the popup down — dismissing it a few
  milliseconds before the first update arrives kills it permanently.

Verify with `adb logcat -s OfflineKeyboard`; `DEBUG_GESTURES` in `KeyboardService` logs every
gesture output.

### Driving gestures from adb

`adb shell input swipe` is useless for testing a long-press-then-drag: it interpolates
immediately, so the gesture becomes a glide before the long-press timer fires. Use
`input motionevent`, which sends each event separately and lets real time pass in between:

```sh
adb shell input motionevent DOWN 624 2138
sleep 0.9                                   # long-press timer fires -> trackpad
adb shell input motionevent MOVE 649 2138
adb exec-out screencap -p > shot.png
adb shell input motionevent UP 649 2138
```

Still single-pointer only, so the two-finger selection gesture remains manual.

---

## Measuring a reference keyboard instead of eyeballing it

The palette and geometry in `Metrics` and `KeyboardView`'s `Theme` were measured off Gboard on
the device, not guessed. Screenshot it, then read pixels with ImageMagick.

Sample a colour at a point:

```sh
magick gboard.png -format "%[pixel:p{57,1682}]" info:
```

Find key edges by scanning a row (or column) and printing where the colour changes — this
gives exact key widths, gaps and margins:

```sh
# horizontal: key edges across row 1
magick gboard.png -crop 1080x1+0+1682 +repage txt:- \
  | awk -F'[,:]' 'NR>1{print $1, $3}' \
  | awk '{if($2!=prev){print $1": "$2; prev=$2}}'

# vertical: row tops and bottoms, at an x inside keys on every row
magick gboard.png -crop 1x820+575+1440 +repage txt:- \
  | awk -F'[,:]' 'NR>1{print $2, $3}' \
  | awk '{v=$2; if(v!=prev){print (1440+$1)": "v; prev=v}}'
```

Pick the scan column carefully: near a key's rounded corner the run is shorter than the key,
and the inset home row is background at the far left, both of which give wrong answers. Use a
column that lands inside a key on all four rows (x=575 works on this device).

The same technique verifies our own output — screenshot both keyboards and compare the edge
lists directly.

---

## Toolchain

Version constraints are documented in `README.md` and are not arbitrary — AGP 9 (not 8),
CMake 3.31 (not 4.x), JDK 21 (not 26). Read that table before "upgrading" anything.

`gradle wrapper` and every `./gradlew` invocation need `JAVA_HOME` pointing at JDK 21:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
```

`gradle.properties` pins `org.gradle.java.home` for the daemon, but the `gradlew` launcher
script itself still needs a JVM on `PATH`.

---

## Working environment (macOS)

- **`timeout` does not exist on macOS.** Commands written as `timeout 20 adb ...` fail with
  `command not found`, and because the failure is per-command it can look like the *device*
  returned nothing. Use `gtimeout` (`brew install coreutils`) or omit it.
- **The dotfiles repo installs git hooks into every new repo.** A `commit-msg` hook runs
  `git prevent`, which greps the commit message, the `GIT_AUTHOR_*` / `GIT_COMMITTER_*`
  environment variables, blobs, branches and tags against `.git/info/bad_strings` (personal
  name and number fragments). Commit with the repo's configured identity and never pass
  `-c user.name=` / `-c user.email=` overrides — the hook will abort the commit.
