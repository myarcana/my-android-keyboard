# Offline Keyboard

An Android IME that behaves like the iOS keyboard, supports English + Chinese
(Traditional/Taiwan and Simplified/mainland), and never touches the network.

Full design and phase plan: `docs/PLAN.md`.
How the granular cursor and selection work: `docs/CURSOR_AND_SELECTION.md`.
Environment gotchas: `NOTES.md`.

## Offline guarantee

The app declares **no `INTERNET` permission**. This is structural, not a policy — without
that permission Android will not let the process open a socket, so nothing typed or spoken
can leave the device even in principle.

The `checkDebugHasNoInternet` / `checkReleaseHasNoInternet` Gradle tasks parse the *merged*
manifest and fail the build if the permission ever appears, including via a transitive
dependency. They run automatically as part of `assemble`.

Verify independently at any time:

```sh
aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk
```

## Toolchain

Installed via Homebrew, no sudo required:

| Component | Version | Location |
|---|---|---|
| JDK | OpenJDK 21 | `/opt/homebrew/opt/openjdk@21` |
| Android SDK | platform 36, build-tools 36.1.0 | `/opt/homebrew/share/android-commandlinetools` |
| NDK | 27.3.13750724 (r27d) | `$ANDROID_HOME/ndk/` |
| CMake | 3.31.6 | `$ANDROID_HOME/cmake/` |
| Gradle | 9.7.1 (wrapper) | — |
| AGP | 9.3.2 | — |

Three version choices are deliberate and should not be casually "upgraded":

- **CMake 3.31.6, not 4.x.** CMake 4 removed support for `cmake_minimum_required(<3.5)`,
  which librime's dependency tree (boost, leveldb, yaml-cpp) still declares. Phase 3 will
  not build on CMake 4.
- **AGP 9.x, not 8.x.** Gradle 9.6 removed an internal API that AGP 8 depends on; the two
  cannot be paired. AGP 9 also provides Kotlin itself, so there is deliberately no
  `org.jetbrains.kotlin.android` plugin in the build files — adding it back is an error.
- **JDK 21, not 26.** Homebrew's `gradle` formula pulls JDK 26 as a dependency and would
  otherwise use it; AGP does not support it. Pinned via `org.gradle.java.home` in
  `gradle.properties`.

## Build

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then enable "Offline Keyboard" in Settings → System → Languages & input → On-screen keyboards.

`local.properties` (git-ignored) points at the SDK; recreate with:

```sh
echo "sdk.dir=/opt/homebrew/share/android-commandlinetools" > local.properties
```

## Status

- **Phase 0 — toolchain and skeleton: complete.** Builds, installs, offline guarantee
  enforced and negative-tested.
- Phase 1 — iOS layout + gesture state machine: next.
- Phases 2–5 — glide typing, Chinese input, dictation, suggestion bar: see `docs/PLAN.md`.
