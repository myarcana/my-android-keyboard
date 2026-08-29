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

### CursorAnchorInfo reports the selection's *start*, not the end you are dragging

`insertionMarkerHorizontal` follows `getSelectionStart()`. With a selection in place that is the
**left** end, so while extending rightwards the reported position never moves and any control
loop steering on it runs away. Measured on device:

```
setSelection(204, 211) -> insH = 409.6   (position of 204)
setSelection(211, 204) -> insH = 548.6   (position of 211)
```

Setting the selection *reversed* therefore makes the app report the end being dragged. The
highlight renders identically -- Android draws min..max -- so this is invisible to the user and
restores a usable feedback signal. `KeyboardService.steerSelection` relies on it.

Note that arrow keys will not work with a reversed selection: shift+arrow moves
`SELECTION_END`, which is the anchor in that arrangement. Move the dragged end with
`setSelection` instead, and collapse briefly if you need a line-aware vertical step.

### Clamp to the line; do not detect a wrap and undo it

Correcting the dragged end of a selection by whole characters will overshoot the end of a
short line. Detecting that it wrapped and reverting looks like it works, but the revert is a
visible jump, and it needs a "blocked" latch to stop it happening again -- and anything that
clears that latch (vertical jitter during a real finger drag, say) restarts the cycle. The
result is a selection that flickers rapidly whenever the marker is past the end of a line.

Clamp the target into the line's own bounds instead. It is stateless, so there is nothing to
get stuck or released at the wrong moment, and the selection simply rests at the line end while
the marker carries on. `KeyboardService.lineBounds` finds the bounds from a snapshot of the
text taken with `getExtractedText` when the drag starts -- no editing happens mid-drag, so it
cannot go stale.

### Swap the span order rather than collapsing, to change line without flicker

An arrow key moves `SELECTION_END`, so to make one move the end you are dragging, that end has
to *be* `SELECTION_END`. Collapsing the selection first works but the highlight visibly
disappears and comes back on every line change.

Swapping the order instead is invisible: `setSelection(anchor, movingEnd)` and
`setSelection(movingEnd, anchor)` describe the *same highlighted range*, since Android draws
min..max. So the selection can be flipped into natural order for the arrow and back into
reversed order for the position feedback, with nothing visible happening in between. A log of a
vertical drag shows the range only ever growing, never collapsing:

```
[19,21] -> [21,19] -> [19,21] -> [19,24] -> [24,19] -> ...
```

Arrow keys still only extend a selection when a genuine `KEYCODE_SHIFT_LEFT` is held --
`META_SHIFT_ON` on the arrow event alone is ignored, see below -- so the shift key is pressed
for the duration of the drag and released when it ends, including on cancel.

### setSelection and sendKeyEvent are not ordered relative to each other

They reach the editor by different routes, so a key event sent immediately after a
`setSelection` can be applied *before* it, and the position echoed back to
`onUpdateCursorAnchorInfo` may be the state before the key was handled.

This bites any "collapse the selection, then press an arrow" sequence: the echo of the
collapse arrives first, and code that re-applies its own state on that echo silently overwrites
the arrow's result. Here it stopped selections crossing paragraph breaks -- every vertical step
was undone the instant it happened, and the symptom was simply that nothing moved.

The fix is to treat a report identical to the pre-step position as "not yet applied" and keep
waiting, with a small tick budget so a genuinely impossible move (the end of the text) does not
wait forever. See `selectionPreStepEnd` in `KeyboardService`.

### CURSOR_UPDATE_MONITOR only fires when the cursor actually moves

This bites in two separate ways, both of which cost real time here.

**It deadlocks a control loop.** Driving one purely from `onUpdateCursorAnchorInfo` means no
movement produces no update, which produces no movement. Anything steering the cursor must also
run when the *input* changes -- for this keyboard, on every pan -- using the last reported
position.

**It never delivers a starting position.** Requesting `MONITOR` alone gives you nothing until
something else moves the cursor, so a gesture that begins with no editing in between gets no
seed and is silently inert. Always ask for both:

```kotlin
ic.requestCursorUpdates(
    InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR,
)
```

The symptom of getting this wrong is oddly specific and easy to misread: the feature works once,
then needs the user to tap in the text before it will work again -- because the tap is what
finally moves the cursor and triggers an update.

### Implicit broadcasts do not reach a backgrounded app

`adb shell am broadcast -a <action>` is silently dropped. Add `-p <package>`:

```sh
adb shell am broadcast -a com.offlinekeyboard.ime.DEBUG_SELECT -p com.offlinekeyboard.ime
```

That broadcast is a debug-only stand-in for the second finger of the selection gesture, which
adb cannot send. It is registered only when `BuildConfig.DEBUG`, so it never exists in a
release build.

### The gesture must start on the space bar, x 421..826

Three separate test runs were wasted starting the drag at x=300 or x=350, which is the
microphone key. The press then becomes a glide rather than a trackpad, the caret never moves,
and it reads exactly like the feature being broken -- once badly enough that a good change was
reverted on the strength of it. On this device (1080px wide) the space bar spans **x 421 to 826**;
450 is a safe start. The row is y 2079..2198.

Derive it rather than guess:

```sh
python3 -c "
w=1080.0; scale=w/360; ku=30.67*scale; gap=4.96*scale; margin=4.33*scale
units=[1.5,1.25,1.25,4.4,2.5]   # 123, globe, mic, space, return
content=sum(units)*ku+(len(units)-1)*gap
x=margin+((w-2*margin)-content)/2
for i,u in enumerate(units):
    if i==3: print(f'space x: {x:.0f}..{x+u*ku:.0f}')
    x+=u*ku+gap"
```

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
