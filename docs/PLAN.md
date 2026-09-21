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
11. Hold backspace to repeat; swipe down on it to delete the word before the cursor, up to clear the line

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

**Done, as the plan originally said: FUTO's `swipe-library`, vendored and built.** It took a
detour first, and the detour is the interesting part.

The plan's second risk said the FUTO decoder was trained on Android key geometry, ours was not,
and coordinate normalisation would be needed. Both halves were false. *Our geometry already is
Android's* — the arrangement is iOS's, but the proportions were measured off Gboard on the device
and Gboard is AOSP's grid, so every letter sits within 0.054 of a key width of where
`rows_qwerty.xml` puts it (`AospGridTest` now asserts this). *And it would not have mattered* —
the encoder is layout-agnostic in the strong sense, taking the key centres as a runtime tensor
through a fixed DCT basis, and the paper measures a layout it had never seen at 97.7% top-1.

Believing that risk produced a hand-written Kotlin decoder instead. It was a reasonable thing —
300 lines, no NDK, 0.23 ms a word — and it lost, on 64 recorded glides from this phone, 79% to
95%. It has been deleted. See NOTES.md for what that comparison measured and why the loser was
not kept as a fallback.

What ships:

- `third_party/swipe-library/` — GPL C++, ExecuTorch + XNNPACK, built for arm64-v8a and x86_64 by
  `tools/fetch_swipe_runtime.sh`. Not committed: it compiles ExecuTorch, which is why it is a
  script and not a checkout.
- `assets/swipe/` — the encoder (2.6 MB), the English decoder (1.2 MB) and the context LM
  (6.2 MB), under FUTO's model weights licence.
- `glide/FutoSwipe.kt` — the coordinate frame, the layout tensor, and staging the models out of
  the APK. The frame is the three letter rows, which nothing downstream can check for us.
- `glide/Lexicon.kt` — still ours, now as the dictionary that constrains the beam search. One
  word list, generated once, so the vocabulary is not a variable.
- One local patch, `tools/patches/swipe-library-trie-jni.patch`: the shipped Kotlin binding has
  no way to load a dictionary at all.

The part that was not foreseen at all is the finger lift. A glide is one stroke in theory and
often is not in practice, and ending the word at the first lift types something nobody asked for.
`GLIDE_LIFTED` and a resume window handle it, and no decoder helps — it is segmentation, not
decoding. `docs/GESTURE_BANK.md` covers how the window gets set from recordings rather than by
feel.

**Milestone met: swiping a word inserts that word.**

## Phase 3 — Chinese input

**This is no longer a from-source build.** `fcitx5-android/prebuilt` publishes per-ABI static
libraries for exactly the set we need — `librime.a` (18.6 MB on arm64-v8a) plus boost, opencc,
marisa, leveldb, glog, yaml-cpp and zstd, each with headers — and it is current (July 2026).
Consuming it turns this plan's largest risk into a checkout and a CMake `find_library`.

One catch, and it is the thing to check first: those artifacts were built with **NDK
28.0.13004108** and we pin 27.3.13750724. CMake matches exactly (3.31.6). Mixing NDK versions
across a C++ static library boundary is not officially supported, so the first step of this
phase is to install NDK 28 and move the pin, not to hope the libc++ ABI lines up.

Schemas: `luna_pinyin` (Simplified), `terra_pinyin` + `bopomofo` (Traditional). Per the
answered question, Traditional gets **both** Zhuyin and Pinyin — Zhuyin needs its own
37-symbol key layout, added to `layout/` as a third layout alongside QWERTY and symbols.

**Build reference: Trime** (`osfans/trime`), not fcitx5-android. Both are alive and both have
solved the NDK build, but Trime *is* an Android IME wrapping librime over JNI — the same shape
as this app — whereas fcitx5-android brings the whole fcitx5 framework along to get there.

Candidate bar renders librime candidates in Chinese modes.

**Milestone: pinyin and zhuyin both produce correct trad/simp characters.**

## Phase 4 — Dictation

**Benchmark before committing to a model.** The "at least as accurate as Apple" bar is a
real constraint and model leaderboards don't predict performance on one person's voice.
First step: record ~20 utterances (English, Mandarin-TW, Mandarin-CN, plus code-switched
zh/en), transcribe each with Apple dictation as the reference, and score candidate models
offline on the Mac before shipping 250MB of assets into the APK.

Candidates, routed **per keyboard language** — we know the target language at dictation time
because the user already switched layouts, which beats a single generalist bilingual model.

The shortlist as of August 2026, all int8 exports in sherpa-onnx layout. `docs/ASR_BENCHMARK.md`
is the harness that scores them on real recordings; `tools/asr_bench/bench.py` runs it.

| Model | Size | Languages |
|---|---|---|
| SenseVoice-small | 239 MB | zh, yue, en, ja, ko |
| Dolphin-small-ctc | 250 MB | zh + dialects |
| Moonshine-base-en | 287 MB | en |
| FireRedASR2-ctc | 776 MB | zh, en |
| Qwen3-ASR-0.6B | 996 MB | 52 languages |
| **Breeze-ASR-25** | 1774 MB | **Taiwanese Mandarin**, en, code-switching |

Breeze-ASR-25 (MediaTek Research, a Whisper-large-v2 fine-tune) is the find: the only candidate
actually trained on Taiwanese Mandarin with zh/en code-switching, and it emits Traditional
characters natively, which would delete step 3 below in Traditional mode. It is also the
largest by a factor of two, in a process Android is willing to kill, so it has to win the
benchmark rather than be assumed to deserve its size.

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

**The emoji half is done**, ahead of Phase 3: `candidates/EmojiIndex.kt` over a committed
`assets/emoji_en.tsv`, generated by `tools/build_emoji_index.py`. Requirement 11 landed with
it, since both live in the same two files. Still open here: the Chinese candidates (blocked on
Phase 3) and the typing conveniences.

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

1. **librime NDK build** — was the largest schedule risk; largely retired by consuming
   `fcitx5-android/prebuilt` rather than building the dependency tree ourselves. What remains
   is the NDK 27→28 move that those artifacts require.
2. ~~**FUTO decoder on iOS geometry** — may need coordinate normalization; vendored as source
   so we can fix it.~~ **Retired, and it was never real.** Our letters are on AOSP's grid to
   within 0.054 of a key width, and the model takes key centres as a runtime tensor in any case,
   so a novel layout costs it nothing. Believing this risk is what produced a hand-written
   decoder instead: the cost of a misjudged risk is not always a failure, sometimes it is a
   worse thing built carefully. The real risks turned out to be elsewhere and all three were
   silent — see NOTES.md — and none of them was geometry.
3. **ASR accuracy bar** — may not be reachable at acceptable size. This is measured in
   Phase 4 before investment, not assumed.
4. **APK size** — models push well past 200MB. Fine for sideloading; models copy from assets
   on first run rather than bloating memory.

## Open item

Confirm the phone model and Android version before Phase 0 fixes `minSdk` and the NDK ABI.
