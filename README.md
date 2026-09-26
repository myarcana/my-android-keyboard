# Offline Keyboard

An Android IME that behaves like the iOS keyboard, supports English + Chinese
(Traditional/Taiwan and Simplified/mainland), and never touches the network.

Two apps come out of this repository. `:app` is the keyboard, and it carries the **Gesture
Lab** -- the labelled-gesture collection rig, which lives inside the keyboard's APK because it
reads gestures the IME hands it in-process and reads its bank out of the same sandbox
(`docs/GESTURE_BANK.md`). `:testpad` is a scratch text field to type into while testing, and it
is a separate app because it shares nothing with the keyboard but a developer.

Full design and phase plan: `docs/PLAN.md`.
How the granular cursor and selection work: `docs/CURSOR_AND_SELECTION.md`.
Environment gotchas: `NOTES.md`.

## Offline guarantee

The app declares **no `INTERNET` permission**. This is structural, not a policy — without
that permission Android will not let the process open a socket, so nothing typed or spoken
can leave the device even in principle.

The `checkDebugHasNoInternet` / `checkReleaseHasNoInternet` Gradle tasks parse the *merged*
manifest and fail the build if the permission ever appears, including via a transitive
dependency. They run automatically as part of `assemble`, in **both** modules -- the test pad is
something typed into, so it gets the same guarantee.

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

Two runtimes are fetched rather than committed, and the build needs both:

```sh
tools/fetch_asr_runtime.sh        # sherpa-onnx + SenseVoice, ~290 MB, a download
tools/fetch_swipe_runtime.sh      # FUTO Swipe + ExecuTorch, ~3 GB and a compile
```

The second one builds ExecuTorch from source for the NDK, which takes a while and only has to
happen once. The keyboard runs without it -- glide typing falls back to the Kotlin decoder -- but
the app will not compile without its Kotlin binding.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r testpad/build/outputs/apk/debug/testpad-debug.apk   # optional: the scratch pad
```

or `tools/deploy.sh`, which does both and screenshots the result.

Then enable "Offline Keyboard" in Settings → System → Languages & input → On-screen keyboards.

### Deploying over the tailnet

The phone does not have to be on USB, or even on the same network: with Tailscale on both ends
it is reachable at a stable IP from anywhere. `tools/adb_tailnet.sh` drives that.

```sh
tools/adb_tailnet.sh persist 100.121.46.71 <port>   # once per boot; <port> from the phone
tools/adb_tailnet.sh connect                        # thereafter, no arguments
DEVICE=100.121.46.71:5555 tools/deploy.sh
```

Android's own Wireless debugging is awkward to automate against: it issues a *random* port, and
it disarms whenever Wi-Fi changes or the screen locks, so every reconnect means reading a new
port (and sometimes a new pairing code) off the phone. `persist` runs `adb tcpip 5555` once,
which restarts adbd on a fixed port that is **not** tied to that toggle -- it stays up across
Wi-Fi disconnects, verified by switching Wi-Fi off entirely, and therefore also works when the
phone is on cellular.

Two caveats, both structural:

- **It does not survive a reboot.** adbd reverts to USB-only. Making it permanent needs
  `persist.adb.tcp.port`, which is root-only; this phone is a `user` build, so after a reboot
  you re-enable Wireless debugging once and re-run `persist`.
- **Expect ~170 KB/s** when there is no direct path and traffic goes through a DERP relay, which
  is the normal case for a phone behind carrier CGNAT. The keyboard APK is ~320 MB because of
  the 229 MB SenseVoice model, so a full install takes about half an hour; code-only changes to
  the test pad take seconds. USB from a laptop remains far quicker for rapid iteration.

`local.properties` (git-ignored) points at the SDK; recreate with:

```sh
echo "sdk.dir=/opt/homebrew/share/android-commandlinetools" > local.properties
```

## Status

- **Phase 0 — toolchain and skeleton: complete.** Builds, installs, offline guarantee
  enforced and negative-tested.
- **Phase 1 — iOS layout and the gesture state machine: complete.** Flick-down symbols, the
  spacebar trackpad with 2D cursor and selection, and hold-backspace with swipe-up to clear the
  line and swipe-down to delete a word. Thresholds fitted
  to a bank of recorded gestures rather than by feel: `docs/GESTURE_BANK.md`.
- **Phase 2 — glide typing: complete.** FUTO Swipe's neural models through the vendored
  `swipe-library`, chosen over the Kotlin decoder written first by scoring both on the same
  recorded glides — 95% against 79%. Both are still wired up, and a lenient window covers the
  mid-glide finger lifts that would otherwise type a word nobody asked for.
- **Phase 4 — dictation: complete.** SenseVoice via sherpa-onnx, chosen by measurement against
  Apple: `docs/ASR_BENCHMARK.md`.
- **Phase 3 — Chinese input: pinyin complete.** Two language models in one asset: mainland
  Simplified, and a native Taiwan Traditional one with its own readings and corpus weights.
  Sentence-level decoding, fuzzy pinyin, abbreviations, and a user dictionary that learns
  corrections. Zhuyin is not built; see below.
- **Phase 5 — the suggestion bar: complete.** Emoji and Chinese ranked together on one
  probability scale, so the bar follows what the letters could mean rather than which language
  is selected. See below.

## Chinese input

Not librime. `docs/PLAN.md` planned to vendor it through the NDK; what is here instead is a
pure-Kotlin engine over a compiled dictionary, which builds with no native toolchain and is
unit-testable from the JVM like the rest of the repo. The quality comes from the data and from
decoding the whole input at once, rather than from C++:

- **Sentence-level Viterbi decoding**, not per-syllable lookup. `jintiantianqihenhao` produces
  今天天气很好 in one pass, scoring word frequencies as log-probabilities with character bigrams
  to break ties between homophones. Looking each syllable up on its own cannot do this, and it
  is the single biggest difference between a usable pinyin keyboard and a toy one.
- **Fuzzy pinyin** (zh/z, ch/c, sh/s, n/l, f/h, ang/an, ing/in), ranked below the exact
  spelling rather than merged with it, so `zongguo` still finds 中国 and `lan` still means 蓝.
- **Abbreviations**: `bjdx` → 北京大学, including mixed forms like `beijingdx`.
- **Two language models, not one dictionary and a converter.** Taiwan Traditional is its own
  model — McBopomofo's Traditional vocabulary, keyed by Taiwan readings and weighted by a Taiwan
  corpus — merged into the same asset as the mainland one, with a weight per region on every
  entry. This is what conversion could not do: 蚵仔煎 is absent from the mainland dictionary
  entirely (rime-ice normalises it to 蚝仔煎, read `hao zai jian`), and 軟體 and 網路 are read
  `ruan ti` and `wang lu`, so no amount of rewriting the *output* of a `ruan jian` lookup
  reaches them. Typing `niuroumian` gives 牛肉麵 first in Taiwan mode and 牛肉面 first in
  mainland mode, because both are real entries under one key with their own corpus weights.
- **Taipei place names**, which no upstream dictionary carries: every Metro station, district,
  里, registered street and named landmark in the city — 2,562 names from government open data,
  each read the way an official romanisation of it spells and weighted by how often it is
  actually written in Taiwan text. `zhongxiaoxinsheng` gives 忠孝新生 first, where before it
  came fourth behind 中小新生.
- **A user dictionary**, so a correction sticks. It never leaves the device.

**Zhuyin is deliberately absent.** It needs its own 37-symbol key layout, which is a keyboard
rather than a setting; the subtype that promised it has been removed until that layout exists.

## The suggestion bar

One bar, one ranking. Emoji and Chinese are scored as log-probabilities on the same scale and
sorted together, so what appears follows from what the letters could plausibly mean rather than
from which language is selected:

| typed | what the bar shows |
|---|---|
| `happy`, `sad`, `yes` | emoji only — these are English words and not pinyin |
| `niuroumian` | 牛肉面 / 牛肉麵 — no emoji is named anything like it |
| `ezijian` | 蚵仔煎 (Taiwan model) |
| `ha` | 哈 alongside 😂 🤣 — genuinely ambiguous, so both appear |
| `you`, `like`, `women` | emoji only, though all three are valid pinyin |

That last row is the interesting one. Pinyin is written in the same 26 letters as English, and
in isolation the Chinese reading is often the commoner string — 有 outscores 牛肉面 on raw
frequency. The evidence that settles it is not about Chinese at all: it is that the letters are
*already an English word*, charged as that word's own log-probability. So `the` suppresses its
Chinese readings heavily, `beijing` — a rare English loanword — barely at all, and 北京 still
wins there.

`tools/rank_preview.py` prints the same scores the app computes, for any query, in either model.

### Licence: the dictionary is GPL-3.0

`app/src/main/assets/pinyin.bin` is built from [rime-ice](https://github.com/iDvel/rime-ice)
(GPL-3.0 only), [McBopomofo](https://github.com/openvanilla/McBopomofo)'s dictionary data (MIT)
for the Taiwan model, plus [OpenCC](https://github.com/BYVoid/OpenCC)'s conversion tables
(Apache-2.0), plus the Taipei place names in `tools/places/taipei.tsv`, generated from the
Ministry of the Interior, Taipei City and Chunghwa Post open datasets under the
[Open Government Data License, version 1.0](https://data.gov.tw/license) (attribution only; the
datasets are listed at the top of `tools/build_taipei_places.py`). Their weights are counts
from [PTT-pretrain-zhtw](https://huggingface.co/datasets/yuhuanstudio/PTT-pretrain-zhtw)
(Apache-2.0) and [Traditional Chinese Common Crawl](https://huggingface.co/datasets/jed351/Traditional-Chinese-Common-Crawl-Filtered);
only the counts ship, not the text. rime-ice was chosen over
permissively-licensed alternatives because it ships real usage weights and CC-CEDICT does not —
and ranking is most of what makes candidates feel right. Only rime-ice carries a copyleft
obligation; the others add none.

The consequence is real: **GPL-3.0 would apply to this app if it were ever distributed.** That
is an acceptable trade for a personal keyboard and is not one for an app store. To change it,
rebuild the asset from CC-CEDICT (CC-BY-SA 4.0) and accept weaker ranking; the sources are named
at the top of `tools/build_pinyin_dict.py`.
