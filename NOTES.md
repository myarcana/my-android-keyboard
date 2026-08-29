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

### Never `am force-stop` the keyboard's own package

Force-stopping `com.offlinekeyboard.ime` kills the running IME, and Android immediately falls
back to another installed keyboard (Gboard here). The next screenshot then shows *Gboard*,
which is easy to mistake for "my layout changes did nothing". Reinstalling alone does not
switch back — re-select it explicitly:

```sh
adb shell ime set com.offlinekeyboard.ime/.KeyboardService
```

`adb install -r` replaces the APK without needing a force-stop, so there is no reason to do it.

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
