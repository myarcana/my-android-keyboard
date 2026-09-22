#!/usr/bin/env python3
"""Generate the keyword-spotter keywords file for spoken punctuation.

    tools/build_punctuation_keywords.py

Reads the ARPAbet pronunciation dictionary that ships inside the keyword-spotter tarball
(`tools/asr_bench/en.phone`, placed there by `tools/fetch_asr_runtime.sh`) and writes
`app/src/main/assets/asr/punctuation_keywords.txt`.

Why this exists
---------------
The keyword spotter does not take words. Each line of its keywords file is a sequence of
*model tokens* followed by `@<id>`, and for this model the English tokens are ARPAbet phonemes
while the Chinese ones are pinyin initials and tone-marked finals:

    K AA1 M AH0 @COMMA
    d òu h ào @COMMA_ZH

So "comma" has to be looked up in the pronunciation dictionary and spelled out, and a typo in
that spelling is not a compile error -- it is a keyword that silently never fires. Generating
the file from the dictionary, and validating every token against `tokens.txt`, is what turns
that class of mistake into a build failure.

The `@id` is what the Kotlin side matches on, so it is a stable identifier rather than the
spoken words: `PunctuationCommands.kt` maps id to mark, and the two must agree.
"""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PHONE_DICT = ROOT / "tools" / "asr_bench" / "en.phone"
TOKENS = ROOT / "app" / "src" / "main" / "assets" / "asr" / "kws" / "tokens.txt"
OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "asr" / "punctuation_keywords.txt"

# Spoken English -> keyword id. The id is the contract with PunctuationCommands.kt.
#
# Multi-word commands are looked up word by word and concatenated, which is what the dictionary
# supports: it has one entry per word, so "question mark" is QUESTION + MARK.
ENGLISH: list[tuple[str, str]] = [
    ("comma", "COMMA"),
    ("period", "PERIOD"),
    ("full stop", "FULL_STOP"),
    ("question mark", "QUESTION_MARK"),
    ("exclamation mark", "EXCLAMATION_MARK"),
    ("exclamation point", "EXCLAMATION_POINT"),
    ("semi colon", "SEMICOLON"),
    ("colon", "COLON"),
    ("new line", "NEW_LINE"),
    ("new paragraph", "NEW_PARAGRAPH"),
    ("open quote", "OPEN_QUOTE"),
    ("close quote", "CLOSE_QUOTE"),
    ("dash", "DASH"),
    ("hyphen", "HYPHEN"),
]

# Spoken Chinese -> keyword id, written the way this model's tokens are: initial then
# tone-marked final, one pair per syllable. Taken from the model's own sample keywords file
# rather than generated, because there is no pinyin dictionary in the tarball.
CHINESE: list[tuple[str, str]] = [
    ("d òu h ào", "COMMA_ZH"),
    ("j ù h ào", "PERIOD_ZH"),
    ("w èn h ào", "QUESTION_MARK_ZH"),
    ("g ǎn t àn h ào", "EXCLAMATION_MARK_ZH"),
    ("f ēn h ào", "SEMICOLON_ZH"),
    ("m ào h ào", "COLON_ZH"),
    ("d ùn h ào", "ENUMERATION_ZH"),
    ("h uàn h áng", "NEW_LINE_ZH"),
]


def load_tokens() -> set[str]:
    if not TOKENS.exists():
        sys.exit(f"missing {TOKENS}\nRun tools/fetch_asr_runtime.sh first.")
    tokens = set()
    for line in TOKENS.read_text(encoding="utf-8").splitlines():
        parts = line.rsplit(" ", 1)
        if len(parts) == 2:
            tokens.add(parts[0])
    return tokens


def load_pronunciations() -> dict[str, list[str]]:
    if not PHONE_DICT.exists():
        sys.exit(f"missing {PHONE_DICT}\nRun tools/fetch_asr_runtime.sh first.")
    pron: dict[str, list[str]] = {}
    for line in PHONE_DICT.read_text(encoding="utf-8", errors="replace").splitlines():
        parts = line.split()
        if len(parts) < 2:
            continue
        word = parts[0].upper()
        # The dictionary lists alternates as WORD(2); keep only the primary pronunciation.
        if word.endswith(")"):
            continue
        pron.setdefault(word, parts[1:])
    return pron


def main() -> None:
    tokens = load_tokens()
    pron = load_pronunciations()

    lines: list[str] = []
    problems: list[str] = []

    for spoken, keyword_id in ENGLISH:
        phones: list[str] = []
        for word in spoken.split():
            key = word.upper()
            if key not in pron:
                problems.append(f"{spoken!r}: no pronunciation for {word!r}")
                break
            phones.extend(pron[key])
        else:
            unknown = [p for p in phones if p not in tokens]
            if unknown:
                problems.append(f"{spoken!r}: tokens not in model: {unknown}")
            else:
                lines.append(f"{' '.join(phones)} @{keyword_id}")

    for pinyin, keyword_id in CHINESE:
        unknown = [p for p in pinyin.split() if p not in tokens]
        if unknown:
            problems.append(f"{pinyin!r}: tokens not in model: {unknown}")
        else:
            lines.append(f"{pinyin} @{keyword_id}")

    # Every token the parser cannot resolve is dispatched on its first character, and three of
    # those branches call std::stof on the rest of the token. Checking here keeps that failure
    # -- a process abort, not a load error -- out of the shipped asset.
    for line in lines:
        for word in line.split():
            if word.startswith("@"):
                continue
            if word[0] in ":#":
                problems.append(f"{line!r}: {word!r} would be parsed as a score or threshold")
            elif word not in tokens:
                problems.append(f"{line!r}: {word!r} is not a model token")

    if problems:
        for p in problems:
            print(f"error: {p}", file=sys.stderr)
        sys.exit("refusing to write a keywords file with entries that cannot fire")

    # The file gets no header, because it has no comment syntax to write one in.
    #
    # sherpa-onnx's EncodeKeywords dispatches any token missing from tokens.txt on its first
    # character, and '#' is claimed as the per-keyword threshold prefix (':' is the boost
    # prefix, '@' the id). A line beginning "# Generated by ..." therefore reaches
    # std::stof("") and throws std::invalid_argument through a JNI frame -- which is not a
    # Kotlin exception, so the catch in KeywordSpotting.load cannot see it and the whole IME
    # process aborts. A documented file that kills the keyboard on load is a bad trade; the
    # explanation lives in this generator's docstring instead.
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {OUTPUT.relative_to(ROOT)} ({len(lines)} keywords)")


if __name__ == "__main__":
    main()
