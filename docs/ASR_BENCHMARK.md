# Choosing the dictation model

Phase 4's accuracy bar is "at least as good as Apple's", and that is a claim about *your* voice.
No published leaderboard measures Taiwanese Mandarin with English code-switching, which is the
case that matters most here, so the model is chosen by measurement rather than by reputation.

Everything runs on the Mac. Nothing is downloaded onto the phone until a model has won.

## The candidates

All six are int8 exports in sherpa-onnx's own layout, so the runtime that scores them here is
the runtime that will run on the phone — a model that wins under some other inference stack is
not evidence about ours.

| | size | languages | why it is here |
|---|---|---|---|
| `sensevoice` | 239 MB | zh, yue, en, ja, ko | the plan's original pick; smallest credible multilingual model |
| `dolphin-small` | 250 MB | zh + dialects | Chinese-dialect focused |
| `moonshine-base-en` | 287 MB | en | English only; the likely English-mode pick |
| `firered2-ctc` | 776 MB | zh, en | 2026 SOTA Mandarin family (2.89% CER on public benchmarks) |
| `qwen3-asr-0.6b` | 996 MB | 52 languages | LLM-based, Feb 2026 |
| `breeze-asr-25` | 1774 MB | Taiwanese Mandarin, en, code-switching | the only candidate trained on the target accent |

`breeze-asr-25` is MediaTek Research's Whisper-large-v2 fine-tune. It is the only one of the six
built for Taiwanese Mandarin and zh/en code-switching, and it emits Traditional characters
natively — if it wins, the OpenCC `s2twp` step disappears from Traditional mode. It is also by
far the largest, and an IME is a process Android is willing to kill, so it has to earn its size
rather than be assumed to deserve it.

## Setup

```sh
python3 -m venv .venv-asr
.venv-asr/bin/pip install sherpa-onnx soundfile huggingface_hub opencc-python-reimplemented
alias bench='.venv-asr/bin/python tools/asr_bench/bench.py'
```

`opencc` is optional but wanted: without it, script folding is off and the mainland-trained
models are penalised for answering in Simplified, which is not a recognition error.

## Running it

**1. Record.** 24 prompts — six each of English, Taiwanese Mandarin, mainland Mandarin, and
code-switched — in `tools/asr_bench/prompts.tsv`. Two of them dictate punctuation aloud, since
that is how this keyboard will insert it.

```sh
tools/asr_bench/record.sh
```

Press **q** to stop each take — ffmpeg keeps recording until you do, and takes are capped at 60
seconds so a forgotten `q` cannot run away.

Read at your normal messaging pace, in the accent you actually use. A model that wins on careful
diction and loses on the way you really talk is the wrong model. Read each line **once**: a
sentence read twice transcribes as a sentence written twice and ruins that prompt's score.

Each take reports its length and peak level, and offers a re-record if it looks like silence
(wrong input device, muted microphone) or runs long enough to suggest a double read. Recordings
land in `asr-bench/recordings/`, which is git-ignored — it is your voice.

ffmpeg prints an Objective-C warning about Continuity Camera on every AVFoundation call. It has
nothing to do with audio capture and no flag silences it, so the script filters it out.

**2. Get Apple's answer**, so the bar is in the same table. Dictate the same 24 prompts into any
Mac text field with Apple dictation, and save what it heard as a TSV of `id<tab>text`:

```sh
bench apple apple.tsv
```

**3. Fetch and run.** 4.3 GB for all six; individual names work too.

```sh
bench fetch
bench run
bench score
```

## Reading the table

```
model                       en     zh-TW     zh-CN     mixed       all    trad
------------------------------------------------------------------------------
apple                      2.4%      5.1%      6.0%     11.2%      6.2%    100%
sensevoice                 ...
```

Everything is scored by character error rate, whitespace removed, with spoken numbers folded to
digits on both sides.

English started out scored by word, on the usual reasoning that CER hides word-level
substitutions. Measured, that was wrong. Several of these models apply inverse text
normalisation — SenseVoice writes "four thirty" as `430`, "fifteen" as `15`, and "J K four nine
two" as `jk492`. It heard every word correctly and chose a different way to write it down, and
word-level alignment scored that as failure: SenseVoice and Moonshine both came out at 31%
English, when the truth is 4% and 6%. The models also disagree about whether "terminal two" is
one token or two. Word boundaries here measure the spacing convention, not the hearing.

**CER is measured with both sides folded to Simplified**, and script correctness is reported
separately as `trad`. This matters: a model that recognises every word perfectly but answers in
Simplified has not misheard anything. Converting it is a step the keyboard already plans, so
counting it as a recognition error would rank the mainland-trained models below where they
belong. `trad` is the share of script-specific characters that came back Traditional, over the
zh-TW and mixed prompts — a model at 100% there needs no conversion step at all.

`bench score` ends by naming any prompt that **every** model returned much longer than the
line. One model erring is a model; all of them erring identically on one prompt is the
recording — a false start, or the line read twice — and that error belongs to the take rather
than to anything being measured. Re-record those and run it again.

`bench run` also prints load time and RTF per model. On the Mac these are not the phone's
numbers, but a model that is slow here will not be fast there, and load time is what the user
waits through the first time they tap the microphone.

## The Whisper runtime caveat

`breeze-asr-25` is a Whisper fine-tune, and **sherpa-onnx's Whisper path drops characters from
Chinese**. Its row is marked `*` and its Chinese columns are not comparable with the others.

This was established, not assumed. Breeze dropped characters from plain Mandarin — 麻辣烫 as 麻,
地铁 as 地, 爬山 as 山 — while transcribing the code-switched prompts almost perfectly, which is
not how a weak model fails. Four explanations were ruled out in turn:

- **Silence or padding** — trimming the takes to a third of their length changes nothing.
- **Quantisation** — the unquantised 6.2 GB weights drop the same characters, identically.
- **Vocabulary** — the two shipped token files are byte-identical, 50257 entries.
- **The audio** — SenseVoice hears every one of those words correctly on the same recordings.

What isolates it is running **stock Whisper-small through both runtimes**. Through sherpa-onnx
it drops the same characters Breeze does; through ctranslate2 the same weights return 豆腐,
周末, 晴天, 地铁 and 迟到 intact. Different weights, same runtime, same failure.

It is upstream [k2-fsa/sherpa-onnx#2900](https://github.com/k2-fsa/sherpa-onnx/issues/2900) —
over 3× the CER of faster-whisper on Chinese, attributed to missing Whisper decoding heuristics
(token suppression, language conditioning). No exposed parameter works around it: `tail_paddings`
and language conditioning make no difference.

Two consequences:

1. Breeze's `en` and `mixed` scores were achieved *despite* this, so they are a floor rather
   than a ceiling. Its true Chinese numbers are unmeasured.
2. sherpa-onnx is the planned Android runtime. Until this is fixed, a Whisper-derived model
   cannot ship on the Chinese modes whatever it scores here — so this is a blocker on the
   engine, not a question about the model.

The non-Whisper candidates use different code paths and are unaffected. Their numbers stand.

## Result, 30 August 2026

```
model                       en     zh-TW     zh-CN     mixed       all    trad
------------------------------------------------------------------------------
apple                     5.1%      4.3%      7.8%     58.9%     18.4%     61%
sensevoice                4.0%      3.2%      1.9%      7.9%      4.5%      0%
qwen3-asr-0.6b            0.0%      2.1%      1.9%     20.5%      5.6%      0%
breeze-asr-25 *           1.8%      7.4%     12.6%      0.7%      4.2%    100%
firered2-ctc             13.0%      5.3%      1.9%     24.5%     12.8%      0%
moonshine-base-en         5.8%         -         -         -         -      -
dolphin-small           100.0%     12.8%      2.9%     66.9%     62.8%      8%
```

**Ship SenseVoice, for every mode.** 239 MB, the smallest candidate, and it beats Apple in all
four columns — 4.5% against 18.4% overall.

Three things this measured that no leaderboard would have:

**Code-switching is the whole game, and it is where Apple collapses.** Every model handles clean
Mandarin; four of them sit at 1.9–3.2% on zh-CN. Mixed zh/en separates them completely, and
Apple scores 58.9% — `這個 bug 我已經 fix 好了` came back as `这个包裹我已经好了你退下的`. Apple
dictation makes you pick a language, and picking one breaks the sentences this keyboard exists
to type. That single column is the case for building this at all.

**Public Mandarin rankings invert here.** FireRedASR2 is 2026 SOTA on published Mandarin
benchmarks and lands at 24.5% on code-switched speech; Qwen3-ASR at 20.5%. SenseVoice, a 2024
model a third of their size, gets 7.9%.

**The per-language routing in the plan is unnecessary.** It assumed a dedicated English model
alongside a Chinese one. SenseVoice beats Moonshine at English (4.0% against 5.8%) while also
doing Chinese, so the second model does not earn its 287 MB. One model, all three languages.

Two alternatives worth remembering rather than adopting:

- **Qwen3-ASR-0.6B** is word-perfect on English — 0.0%, differing from the prompts only in
  punctuation — and best on Taiwanese Mandarin at 2.1%. It costs 996 MB and gives up
  code-switching (20.5%). Worth revisiting only if English dictation becomes the dominant use.
- **Breeze-ASR-25** is the best thing here on both English (1.8%) and code-switching (0.7%),
  and the only model returning Traditional characters, and it scored that *through* the broken
  Whisper path below. It is blocked on the runtime, not on merit.

### Which models write Taiwanese characters

`trad` counts only the characters where the two scripts differ — most Han characters are shared,
and counting those buries the signal under a 72–75% floor every model reaches just by writing
Chinese. A character is simplified if the keyboard's own `s2twp` conversion would change it,
which is the same judgement the keyboard will make at runtime.

**Only Breeze-ASR-25 writes Taiwanese.** 100%, with nothing at all for `s2twp` to change across
the zh-TW and mixed prompts. Every other candidate is Simplified: SenseVoice, Qwen3-ASR and
FireRedASR2 at 0%, Dolphin at 8%.

Apple manages 61%, which is worse than it sounds — it is not partially converting, it is
switching wholesale. It writes clean Traditional for plain Mandarin (`我剛剛把資料傳到你的信箱了`)
and drops to Simplified the moment the sentence code-switches (`這個 bug 我已經 fix 好了` comes
back as `这个包裹我已经好了`). Losing the script and losing the words happen together.

So the `s2twp` step stays, and it is doing real work rather than tidying edge cases: 39 of 150
Han characters in SenseVoice's Taiwanese output need converting.

## Deciding

The bar is the `apple` row. Beat it in all four columns and the choice is made. The likely
outcome is a split — a small English model plus one Chinese model — which the plan already
allows for, since the keyboard knows the target language at dictation time: the user has
already switched layouts.

If nothing beats Apple, that is a real result and worth knowing before 250 MB to 1.8 GB of
assets goes into the APK.
