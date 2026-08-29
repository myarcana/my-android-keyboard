# Offline iOS-style Android Keyboard

## Context

`/Users/me/Projects/my-android-improvements/my-android-keyboard` is empty and the machine has no Android toolchain
(no JDK, no SDK/NDK, no Gradle; the `adb` shell alias points at a path that doesn't exist).
This is a from-scratch build.

The goal is a personal Android IME that behaves like the iOS keyboard but goes further, and
never touches the network. Nine requirements drive the design:

1. 100% offline, including dictation
2. iOS keyboard layout, exact
3. iPadOS-style flick-down on a key to type its secondary digit/symbol
4. Continuing that swipe further turns it into glide typing for whole words
5. Long-press spacebar → 2D cursor movement (up/down lines, not just horizontal)
6. English
7. Chinese Traditional (Taiwan dictionaries) + Simplified (mainland dictionaries)
8. Offline dictation for Taiwanese Mandarin, mainland Mandarin, and English
9. Suggestion bar shows only emoji or Chinese candidates — never English word suggestions
10. Switching language never destroys an unfinalized composing buffer

Two decisions were settled during planning:

- **No autocorrect at all.** Tapped keys produce exactly those letters, always. Word decoding
  exists *only* to turn a glide gesture into a word. Nothing typed is ever silently rewritten.
- **No automatic punctuation in dictation.** Punctuation is only inserted when spoken
  ("comma", "句號"). The accuracy bar for speech-to-text is "at least as good as Apple's".

## Architecture

We write the layer that makes this keyboard distinctive, and vendor the three hard engines
as in-tree source so they can be modified.

```
OUR CODE — Kotlin
  ime/       InputMethodService, raw composing buffer, language switching
  layout/    iOS-exact key geometry, data-driven (ratios, not pixels)
  gesture/   TouchFSM: tap | flick-down | long-press | glide | spacebar trackpad
  candidates/ emoji index (English mode) + CJK candidates (Chinese mode)
  asr/       recorder, endpointing, spoken-punctuation mapping

VENDORED C++ (in-tree, built by our CMake, modifiable)
  third_party/swipe-library/   FUTO Swipe — glide decoding
  third_party/librime/         Chinese: pinyin + zhuyin, trad + simp
  third_party/sherpa-onnx/     dictation
  third_party/opencc/          simplified → Taiwan-traditional
```

Why not fork HeliBoard or FUTO Keyboard: both are AOSP LatinIME forks, so their key layout
and touch handling are the same 15-year-old Java pipeline — and that pipeline is precisely
the layer requirements 2–5 live in. Neither has any CJK support, so librime gets bolted on
either way, and both center an autocorrect/word-suggestion architecture that requirement 9
asks us to delete. FUTO's genuinely valuable piece, the swipe engine, was released
separately as `swipe-library` and can be taken on its own.

**Licensing:** `swipe-library` and `librime` are GPL/BSD-family; vendoring GPL C++ makes the
combined binary GPL if it were ever distributed. Irrelevant for a personal sideloaded build,
but it rules out shipping this commercially. Flagging it once, here.

**Privacy:** the manifest declares **no `INTERNET` permission at all**. That is the
enforcement mechanism, not a policy — the app is structurally incapable of network I/O, and
a CI check will assert the permission never appears.

---

## Phase 0 — Toolchain and skeleton

Install (requires user approval, ~10GB): Temurin JDK 21, Android command-line tools, NDK,
platform-tools. Fix the stale `adb` alias in the shell profile.

Create the Gradle project: `minSdk 29`, Kotlin, NDK/CMake wired up, `git init`, and a
do-nothing `InputMethodService` that can be selected in Android settings and shows a grey box.

**Milestone: the keyboard can be enabled on the phone and typed into.**

## Phase 1 — iOS layout + gesture FSM (the core differentiator)

This is the heart of the project and the reason we aren't forking. Build it before anything else.

- `layout/`: iOS key geometry as data — row ratios, key widths, gaps, the exact
  shift/backspace/globe/123 arrangement, light and dark styling, popup accent sets.
  Rendered with a custom `View` and Canvas, not Compose (IME touch latency matters).
- `gesture/TouchFSM`: one state machine per pointer.

```
DOWN → PRESSED
  hold, no movement          → ACCENT_POPUP   (iOS long-press accents: é è ê ...)
  |dy| > flickThresh
    and |dy| > 1.5·|dx|      → FLICK          (secondary digit/symbol, uncommitted)
  path length > glideThresh
    or pointer exits origin  → GLIDE
  FLICK, then path continues → GLIDE          ← requirement 4, explicitly
```

FLICK stays uncommitted until release precisely so it can still promote to GLIDE. Thresholds
go in a debug overlay screen so they can be tuned by feel on the real device.

- Spacebar trackpad: long-press space → TRACKPAD. Horizontal delta and vertical delta both
  accumulate against a threshold and emit `DPAD_LEFT/RIGHT/UP/DOWN` key events. Vertical uses
  key events rather than `setSelection` because only the text view knows where lines wrap.

**Milestone: English typing feels like iOS, flick-down types symbols, space is a 2D trackpad.**

## Phase 2 — Glide typing

Vendor `swipe-library` from `gitlab.futo.org/keyboard/swipe-library` into `third_party/` as
source, plus the FUTO Swipe models from HuggingFace (`futo-org/futo-swipe`): layout-agnostic
encoder (635K params) + QWERTY decoder (300K) + context LM (1.5M). JNI bridge from the
GLIDE state.

The decoder is QWERTY-specific and our layout is QWERTY, but iOS key geometry differs
slightly from the Android geometry it was trained on. **Verify accuracy empirically on the
real layout early** — if it degrades, the fix is coordinate normalization into the decoder's
expected key grid, which is why we vendor as source rather than link a prebuilt.

**Milestone: swiping a word inserts that word.**

## Phase 3 — Chinese input

Vendor librime. Its dependency set (boost, leveldb, marisa, opencc, yaml-cpp, glog) is the
single largest build-system risk in this plan; crib the NDK toolchain and Gradle module
layout from `fcitx5-android`, which has already solved exactly this.

Schemas: `luna_pinyin` (Simplified), `terra_pinyin` + `bopomofo` (Traditional). Per the
answered question, Traditional gets **both** Zhuyin and Pinyin — Zhuyin needs its own
37-symbol key layout, added to `layout/` as a third layout alongside QWERTY and symbols.

Candidate bar renders librime candidates in Chinese modes.

**Milestone: pinyin and zhuyin both produce correct trad/simp characters.**

## Phase 4 — Dictation

**Benchmark before committing to a model.** The "at least as accurate as Apple" bar is a
real constraint and model leaderboards don't predict performance on one person's voice.
First step: record ~20 utterances (English, Mandarin-TW, Mandarin-CN, plus code-switched
zh/en), transcribe each with Apple dictation as the reference, and score candidate models
offline on the Mac before shipping 250MB of assets into the APK.

Candidates, routed **per keyboard language** — we know the target language at dictation time
because the user already switched layouts, which beats a single generalist bilingual model:

| Mode | Candidate |
|---|---|
| English | dedicated English offline model (Zipformer / Moonshine class) |
| Chinese (both) | SenseVoice-small (~234M, CER 2.96% on AISHELL-1) or Paraformer trilingual |

Live-text UX with high final accuracy: stream a small model for on-screen preview while
speaking, then re-run the buffered audio through the accurate non-streaming model at
endpoint and replace the text.

Post-processing chain:
1. **Strip** SenseVoice's built-in auto-punctuation — the user does not want it.
2. **Spoken punctuation mapping**: "comma"→`,` "period"/"full stop"→`.` "question mark"→`?`
   "new line"→`\n`; 逗號→`，` 句號→`。` 問號→`？` 頓號→`、` 驚嘆號→`！`. Full-width in Chinese
   modes; quotes are 「」 in Traditional and “” in Simplified.
3. **OpenCC `s2twp`** in Traditional mode — Taiwan-traditional with phrase substitution.

**Milestone: dictation matches Apple on the benchmark set, in all three languages.**

## Phase 5 — Suggestion bar and polish

Requirement 9: the bar **never** shows English word suggestions. In English mode it shows
emoji matched from an offline CLDR emoji-keyword index; in Chinese modes it shows candidates.
Plus the iOS conveniences that don't rewrite letters: auto-capitalize after sentence end,
smart quotes, double-space→". ".

## Cross-cutting: the composing buffer (requirement 10)

Implemented once, at IME level, not per-engine. The IME owns a `rawInput: StringBuilder` of
literal keystrokes; the language engines are stateless *views* over it. Switching language
must never call `finishComposingText` with a clear — it re-feeds `rawInput` to the incoming
engine (English mode being the identity view). Typing `nihao`, switching to English, and
seeing `nihao` survive is a test case, not a hope.

---

## Verification

- **Instrumented tests** on the gesture FSM: synthetic touch traces asserting each
  tap/flick/glide/trackpad transition, including flick→glide promotion.
- **Unit tests**: composing-buffer survival across every language-switch pair; spoken
  punctuation mapping per mode; OpenCC trad conversion.
- **CI assertion**: `INTERNET` permission absent from the merged manifest.
- **On-device, per phase**: `adb install` and drive the real keyboard — the phase milestones
  above are each a manual acceptance check on the actual phone.
- **ASR**: scored WER/CER against the Apple-dictation reference set from Phase 4.

## Risks

1. **librime NDK build** — largest schedule risk. Mitigation: copy `fcitx5-android`'s build.
2. **FUTO decoder on iOS geometry** — may need coordinate normalization; vendored as source
   so we can fix it.
3. **ASR accuracy bar** — may not be reachable at acceptable size. This is measured in
   Phase 4 before investment, not assumed.
4. **APK size** — models push well past 200MB. Fine for sideloading; models copy from assets
   on first run rather than bloating memory.

## Open item

Confirm the phone model and Android version before Phase 0 fixes `minSdk` and the NDK ABI.
