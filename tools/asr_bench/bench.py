#!/usr/bin/env python3
"""Score offline ASR models on your own voice, before shipping one into the APK.

The plan's rule is that dictation must be at least as accurate as Apple's, and no leaderboard
predicts performance on one person's voice in one person's accent -- least of all for Taiwanese
Mandarin and zh/en code-switching, which most published benchmarks do not measure at all. So
the models are scored here, on the Mac, against recordings of `prompts.tsv`, and only the
winner gets downloaded onto the phone.

    tools/asr_bench/record.sh              read the prompts aloud, once each
    bench.py fetch                         download the candidates (~4.3 GB)
    bench.py run                           transcribe every recording with every model
    bench.py apple apple.tsv               add Apple's dictation of the same prompts, as the bar
    bench.py score                         the table

Requires the venv described in docs/ASR_BENCHMARK.md.
"""

import argparse
import pathlib
import sys
import time
import unicodedata

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
PROMPTS = pathlib.Path(__file__).resolve().parent / "prompts.tsv"
RECORDINGS = ROOT / "asr-bench" / "recordings"
RESULTS = ROOT / "asr-bench" / "results"
MODEL_DIR = ROOT / "models" / "asr"

# Every candidate is an int8 export in sherpa-onnx's own layout, so the same runtime that will
# run on the phone runs here -- a model that scores well in a different runtime is not evidence.
MODELS = {
    "sensevoice": {
        "repo": "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
        "files": ["model.int8.onnx", "tokens.txt"],
        "mb": 239,
        "langs": "zh, yue, en, ja, ko",
        "note": "the plan's original pick; smallest credible multilingual model",
    },
    "dolphin-small": {
        "repo": "csukuangfj/sherpa-onnx-dolphin-small-ctc-multi-lang-int8-2025-04-02",
        "files": ["model.int8.onnx", "tokens.txt"],
        "mb": 250,
        "langs": "zh + dialects",
        "note": "Chinese-dialect focused",
    },
    "moonshine-base-en": {
        "repo": "csukuangfj/sherpa-onnx-moonshine-base-en-int8",
        "files": [
            "preprocess.onnx",
            "encode.int8.onnx",
            "uncached_decode.int8.onnx",
            "cached_decode.int8.onnx",
            "tokens.txt",
        ],
        "mb": 287,
        "langs": "en",
        "note": "English only; the likely English-mode pick",
    },
    "firered2-ctc": {
        "repo": "csukuangfj2/sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25",
        "files": ["model.int8.onnx", "tokens.txt"],
        "mb": 776,
        "langs": "zh, en",
        "note": "2026 SOTA Mandarin family (2.89% CER on public benchmarks), CTC variant",
    },
    "qwen3-asr-0.6b": {
        "repo": "csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
        "files": [
            "conv_frontend.onnx",
            "encoder.int8.onnx",
            "decoder.int8.onnx",
            "tokenizer/vocab.json",
            "tokenizer/merges.txt",
            "tokenizer/tokenizer_config.json",
        ],
        "mb": 996,
        "langs": "52 languages",
        "note": "LLM-based, Feb 2026",
    },
    "breeze-asr-25": {
        "repo": "MediaTek-Research/Breeze-ASR-25-onnx-250806",
        "files": [
            "breeze-asr-25-half-encoder.onnx",
            "breeze-asr-25-half-encoder.int8.onnx",
            "breeze-asr-25-half-decoder.onnx",
            "breeze-asr-25-half-decoder.int8.onnx",
            "breeze-asr-25-half-tokens.txt",
        ],
        "mb": 1774,
        "langs": "Taiwanese Mandarin, en, code-switching",
        "note": "the only candidate trained on the target accent; outputs traditional natively",
        # See WHISPER_RUNTIME_CAVEAT: its Chinese columns measure the runtime, not the model.
        "family": "whisper",
    },
}


WHISPER_RUNTIME_CAVEAT = """\
sherpa-onnx's Whisper path drops characters from Chinese, so any row marked * understates the
model by a wide margin and its Chinese columns should not be compared with the others.

Established here rather than assumed. Breeze dropped characters from plain Mandarin -- 麻辣烫 as
麻, 地铁 as 地, 爬山 as 山 -- while transcribing the code-switched prompts almost perfectly, which
is not how a weak model fails. Four things were ruled out in turn: silence (trimming the takes
to a third of their length changed nothing), quantisation (the unquantised 6.2 GB weights drop
the same characters), the vocabulary (both shipped token files are byte-identical), and the
audio itself (SenseVoice hears every one of those words correctly on the same recordings).

What isolates it is running stock Whisper-small through both runtimes. Through sherpa-onnx it
drops the same characters as Breeze; through ctranslate2, the same weights return 豆腐, 周末,
晴天, 地铁 and 迟到 intact. Different weights, same runtime, same failure. It is upstream issue
k2-fsa/sherpa-onnx#2900 -- over 3x the CER of faster-whisper on Chinese, from missing Whisper
decoding heuristics. No exposed parameter works around it: tail_paddings and language
conditioning make no difference.

The consequence for the phone is the thing to remember: sherpa-onnx is the Android runtime, so
until this is fixed a Whisper-derived model cannot be shipped on its Chinese modes whatever it
scores here."""


def recognizer(name: str):
    """Builds the sherpa-onnx recognizer for a model, once its files are on disk."""
    import sherpa_onnx

    d = MODEL_DIR / name
    threads = 4
    if name == "sensevoice":
        return sherpa_onnx.OfflineRecognizer.from_sense_voice(
            model=str(d / "model.int8.onnx"),
            tokens=str(d / "tokens.txt"),
            num_threads=threads,
            # Left empty so the model picks the language itself, which is what the keyboard
            # cannot do for it during a code-switched utterance.
            language="",
            use_itn=False,
        )
    if name == "dolphin-small":
        return sherpa_onnx.OfflineRecognizer.from_dolphin_ctc(
            model=str(d / "model.int8.onnx"),
            tokens=str(d / "tokens.txt"),
            num_threads=threads,
        )
    if name == "moonshine-base-en":
        return sherpa_onnx.OfflineRecognizer.from_moonshine(
            preprocessor=str(d / "preprocess.onnx"),
            encoder=str(d / "encode.int8.onnx"),
            uncached_decoder=str(d / "uncached_decode.int8.onnx"),
            cached_decoder=str(d / "cached_decode.int8.onnx"),
            tokens=str(d / "tokens.txt"),
            num_threads=threads,
        )
    if name == "firered2-ctc":
        return sherpa_onnx.OfflineRecognizer.from_fire_red_asr_ctc(
            model=str(d / "model.int8.onnx"),
            tokens=str(d / "tokens.txt"),
            num_threads=threads,
        )
    if name == "qwen3-asr-0.6b":
        return sherpa_onnx.OfflineRecognizer.from_qwen3_asr(
            conv_frontend=str(d / "conv_frontend.onnx"),
            encoder=str(d / "encoder.int8.onnx"),
            decoder=str(d / "decoder.int8.onnx"),
            tokenizer=str(d / "tokenizer"),
            num_threads=threads,
        )
    if name == "breeze-asr-25":
        # A Whisper-large-v2 fine-tune, so it uses sherpa-onnx's Whisper runtime unchanged.
        return sherpa_onnx.OfflineRecognizer.from_whisper(
            encoder=str(d / "breeze-asr-25-half-encoder.int8.onnx"),
            decoder=str(d / "breeze-asr-25-half-decoder.int8.onnx"),
            tokens=str(d / "breeze-asr-25-half-tokens.txt"),
            language="zh",
            task="transcribe",
            num_threads=threads,
        )
    sys.exit(f"unknown model: {name}")


# --- text normalisation -------------------------------------------------------------------

try:
    from opencc import OpenCC

    _to_simplified = OpenCC("t2s").convert
except Exception:  # pragma: no cover - optional dependency
    _to_simplified = None


def strip_punctuation(text: str) -> str:
    return "".join(c for c in text if not unicodedata.category(c).startswith("P"))


# Spoken numbers, for undoing inverse text normalisation. Several of these models silently
# rewrite "four thirty" as "430", "fifteen" as "15" and "J K four nine two" as "jk492" -- they
# heard every word correctly and chose a different way to write it down. Scored raw that cost
# SenseVoice 28 points of English WER, which would have been read as it mishearing numbers.
SPOKEN_NUMBERS = {
    "zero": "0", "oh": "0", "one": "1", "two": "2", "three": "3", "four": "4", "five": "5",
    "six": "6", "seven": "7", "eight": "8", "nine": "9", "ten": "10", "eleven": "11",
    "twelve": "12", "thirteen": "13", "fourteen": "14", "fifteen": "15", "sixteen": "16",
    "seventeen": "17", "eighteen": "18", "nineteen": "19", "twenty": "20", "thirty": "30",
    "forty": "40", "fifty": "50", "sixty": "60", "seventy": "70", "eighty": "80", "ninety": "90",
}


def fold_numbers(text: str) -> str:
    return " ".join(SPOKEN_NUMBERS.get(word, word) for word in text.split())


def normalise(text: str, fold_script: bool) -> str:
    """
    Comparable form. NFKC folds full-width Latin onto ASCII, which every Chinese model emits
    inconsistently and which no reader would call an error.

    Script folding is separate and deliberate. A model that recognises perfectly but answers in
    simplified has not misheard anything -- converting it is a step the keyboard already plans
    (OpenCC s2twp in Traditional mode). Counting it as a recognition error would rank the
    mainland-trained models below where they belong, so CER is measured with both sides folded
    to simplified and script correctness is reported as its own column.
    """
    text = unicodedata.normalize("NFKC", text)
    text = fold_numbers(strip_punctuation(text).lower())
    if fold_script and _to_simplified:
        text = _to_simplified(text)
    return " ".join(text.split())


def edit_distance(a, b) -> int:
    if len(a) < len(b):
        a, b = b, a
    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        current = [i]
        for j, cb in enumerate(b, 1):
            current.append(
                min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (ca != cb))
            )
        previous = current
    return previous[-1]


def cer(reference: str, hypothesis: str) -> tuple[int, int]:
    """
    Character error rate, whitespace removed, for every language including English.

    English was scored by word at first, on the usual reasoning that CER hides word-level
    substitutions. Measured, that was the wrong call: these models disagree about whether
    "terminal two" is one token or two, and write "J K four nine two" variously as "jk492",
    "j k 492" and "J K FOUR N TWO". Word alignment then scores the spacing convention rather
    than the hearing -- it put SenseVoice and Moonshine at 31% English, when normalising
    numbers and dropping spaces puts them at 4% and 6%.
    """
    ref = normalise(reference, fold_script=True).replace(" ", "")
    hyp = normalise(hypothesis, fold_script=True).replace(" ", "")
    return edit_distance(ref, hyp), len(ref)


TRADITIONAL_ONLY = set("這個們來時對開會後點鐘號經過還說話語聲車東馬鳥魚長門問間關電視覺學國圖書館體驗豐灣臺灣灣號")


def script_hits(reference: str, hypothesis: str) -> tuple[int, int]:
    """
    How much of a Traditional prompt came back in Traditional characters.

    Only counts positions where the two scripts actually differ, since most characters are
    shared and would otherwise drown the signal.
    """
    if not _to_simplified:
        return 0, 0
    hit = total = 0
    for char in strip_punctuation(hypothesis):
        simplified = _to_simplified(char)
        if simplified == char:
            continue  # already simplified, or a character the two scripts share
        total += 1
        hit += 1
    for char in strip_punctuation(reference):
        if _to_simplified(char) != char:
            total = max(total, 1)
    return hit, total


# --- data ---------------------------------------------------------------------------------


def load_prompts() -> list[dict]:
    rows = []
    for line in PROMPTS.read_text(encoding="utf-8").splitlines()[1:]:
        if not line.strip():
            continue
        pid, lang, text = line.split("\t")
        rows.append({"id": pid, "lang": lang, "text": text})
    return rows


def load_results(name: str) -> dict[str, str]:
    path = RESULTS / f"{name}.tsv"
    if not path.exists():
        return {}
    out = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        out[parts[0]] = parts[1] if len(parts) > 1 else ""
    return out


# --- commands -----------------------------------------------------------------------------


def cmd_list(_args) -> int:
    total = 0
    for name, m in MODELS.items():
        have = "downloaded" if (MODEL_DIR / name).exists() else "-"
        total += m["mb"]
        print(f"  {name:18} {m['mb']:5} MB  {have:11} {m['langs']}")
        print(f"  {'':18} {m['note']}")
    print(f"\n  {'all':18} {total:5} MB")
    return 0


def cmd_fetch(args) -> int:
    from huggingface_hub import hf_hub_download

    for name in args.models or MODELS:
        m = MODELS[name]
        target = MODEL_DIR / name
        target.mkdir(parents=True, exist_ok=True)
        print(f"{name} ({m['mb']} MB) from {m['repo']}")
        for filename in m["files"]:
            destination = target / filename
            if destination.exists():
                continue
            destination.parent.mkdir(parents=True, exist_ok=True)
            # local_dir downloads straight here. Without it the file lands in the shared HF
            # cache and is then copied, which costs a second 4.3 GB for no benefit -- these
            # models are read once by this script and never shared with anything else.
            hf_hub_download(repo_id=m["repo"], filename=filename, local_dir=str(target))
            print(f"  {filename}")
    return 0


def cmd_run(args) -> int:
    import soundfile

    prompts = load_prompts()
    missing = [p["id"] for p in prompts if not (RECORDINGS / f"{p['id']}.wav").exists()]
    if missing:
        print(f"no recording for: {', '.join(missing)}", file=sys.stderr)
        if len(missing) == len(prompts):
            print("run tools/asr_bench/record.sh first", file=sys.stderr)
            return 1

    RESULTS.mkdir(parents=True, exist_ok=True)
    for name in args.models or MODELS:
        if not (MODEL_DIR / name).exists():
            print(f"{name}: not downloaded, skipping")
            continue
        print(f"{name}: loading", flush=True)
        started = time.time()
        rec = recognizer(name)
        load_seconds = time.time() - started

        lines, audio_seconds, decode_seconds = [], 0.0, 0.0
        for prompt in prompts:
            wav = RECORDINGS / f"{prompt['id']}.wav"
            if not wav.exists():
                continue
            samples, rate = soundfile.read(str(wav), dtype="float32", always_2d=False)
            if samples.ndim > 1:
                samples = samples[:, 0]
            audio_seconds += len(samples) / rate

            began = time.time()
            stream = rec.create_stream()
            stream.accept_waveform(rate, samples)
            rec.decode_stream(stream)
            decode_seconds += time.time() - began
            lines.append(f"{prompt['id']}\t{stream.result.text.strip()}")

        (RESULTS / f"{name}.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8")
        rtf = decode_seconds / audio_seconds if audio_seconds else 0
        print(f"  {len(lines)} utterances, load {load_seconds:.1f}s, RTF {rtf:.2f}")
    return 0


def cmd_apple(args) -> int:
    """Imports Apple's dictation of the same prompts, so the bar sits in the same table."""
    RESULTS.mkdir(parents=True, exist_ok=True)
    source = pathlib.Path(args.file).read_text(encoding="utf-8")
    (RESULTS / "apple.tsv").write_text(source, encoding="utf-8")
    print(f"apple: {len(source.splitlines())} utterances")
    return 0


def cmd_score(_args) -> int:
    prompts = load_prompts()
    names = sorted(p.stem for p in RESULTS.glob("*.tsv")) if RESULTS.exists() else []
    if not names:
        print("no results yet -- run `bench.py run` first", file=sys.stderr)
        return 1
    if _to_simplified is None:
        print("note: opencc not installed, script folding is off\n", file=sys.stderr)

    groups = ["en", "zh-TW", "zh-CN", "mixed"]
    header = f"{'model':20}" + "".join(f"{g:>10}" for g in groups) + f"{'all':>10}{'trad':>8}"
    print(header)
    print("-" * len(header))

    for name in names:
        results = load_results(name)
        errors = {g: [0, 0] for g in groups}
        overall = [0, 0]
        trad_hit = trad_total = 0
        for prompt in prompts:
            if prompt["id"] not in results:
                continue
            hypothesis = results[prompt["id"]]
            distance, length = cer(prompt["text"], hypothesis)
            errors[prompt["lang"]][0] += distance
            errors[prompt["lang"]][1] += length
            overall[0] += distance
            overall[1] += length
            if prompt["lang"] in ("zh-TW", "mixed"):
                hit, total = script_hits(prompt["text"], hypothesis)
                trad_hit += hit
                trad_total += total

        row = f"{name + ' *' if MODELS.get(name, {}).get('family') == 'whisper' else name:20}"
        for g in groups:
            d, n = errors[g]
            row += f"{(100 * d / n):9.1f}%" if n else f"{'-':>10}"
        d, n = overall
        row += f"{(100 * d / n):9.1f}%" if n else f"{'-':>10}"
        row += f"{(100 * trad_hit / trad_total):7.0f}%" if trad_total else f"{'-':>8}"
        print(row)

    print("\nCER, spoken numbers folded to digits; lower is better.")
    print("trad = share of script-specific characters returned in Traditional.")
    if any(MODELS.get(n, {}).get("family") == "whisper" for n in names):
        print("\n* " + WHISPER_RUNTIME_CAVEAT.replace("\n", "\n  "))
    warn_about_recordings(prompts)
    return 0


def warn_about_recordings(prompts) -> None:
    """
    Flags a prompt that every model got long, which means the recording is wrong.

    A model producing a transcript half again longer than the line is one model erring. Every
    model doing it on the same prompt is not: the take contains a false start or a second read,
    and the resulting error rate belongs to the recording rather than to anything being scored.
    Without this the harness quietly blames the models for a re-take nobody noticed.
    """
    per_model = {name: load_results(name) for name in (p.stem for p in RESULTS.glob("*.tsv"))}
    suspect = []
    for prompt in prompts:
        ratios = sorted(
            len(normalise(results[prompt["id"]], fold_script=True).replace(" ", ""))
            / max(1, len(normalise(prompt["text"], fold_script=True).replace(" ", "")))
            for name, results in per_model.items()
            if prompt["id"] in results and results[prompt["id"]]
        )
        if len(ratios) >= 2 and ratios[len(ratios) // 2] > 1.5:
            suspect.append((prompt["id"], ratios[len(ratios) // 2]))

    if suspect:
        print("\nRecordings every model returned long -- re-record these, they are not model error:")
        for pid, ratio in suspect:
            print(f"  {pid}  {ratio:.1f}x the expected length     tools/asr_bench/record.sh {pid}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("list", help="candidates and their sizes").set_defaults(fn=cmd_list)

    fetch = sub.add_parser("fetch", help="download models")
    fetch.add_argument("models", nargs="*", choices=[*MODELS, []])
    fetch.set_defaults(fn=cmd_fetch)

    run = sub.add_parser("run", help="transcribe the recordings")
    run.add_argument("models", nargs="*", choices=[*MODELS, []])
    run.set_defaults(fn=cmd_run)

    apple = sub.add_parser("apple", help="import Apple dictation as the bar to beat")
    apple.add_argument("file", help="TSV of <prompt id><tab><what Apple heard>")
    apple.set_defaults(fn=cmd_apple)

    sub.add_parser("score", help="the table").set_defaults(fn=cmd_score)

    args = parser.parse_args()
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
