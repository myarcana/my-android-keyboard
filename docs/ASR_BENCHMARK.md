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

English is scored by word (WER), everything else by character (CER) — WER is undefined for a
sentence with no spaces, and CER over English hides word-level substitutions.

**CER is measured with both sides folded to Simplified**, and script correctness is reported
separately as `trad`. This matters: a model that recognises every word perfectly but answers in
Simplified has not misheard anything. Converting it is a step the keyboard already plans, so
counting it as a recognition error would rank the mainland-trained models below where they
belong. `trad` is the share of script-specific characters that came back Traditional, over the
zh-TW and mixed prompts — a model at 100% there needs no conversion step at all.

`bench run` also prints load time and RTF per model. On the Mac these are not the phone's
numbers, but a model that is slow here will not be fast there, and load time is what the user
waits through the first time they tap the microphone.

## Deciding

The bar is the `apple` row. Beat it in all four columns and the choice is made. The likely
outcome is a split — a small English model plus one Chinese model — which the plan already
allows for, since the keyboard knows the target language at dictation time: the user has
already switched layouts.

If nothing beats Apple, that is a real result and worth knowing before 250 MB to 1.8 GB of
assets goes into the APK.
