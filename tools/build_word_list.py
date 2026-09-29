#!/usr/bin/env python3
"""
Builds `tools/words/<name>.tsv` from a hand-curated `tools/words/<name>.src`: words no upstream
dictionary carries, with validated readings and measured weights, for `build_pinyin_dict.py`
to merge.

    PYTHONPATH=../zhwork/pylib tools/build_word_list.py --work ../zhwork tools/words/food.src

WHY THIS EXISTS
`xuehuabing` decoded to 雪花宾馆, `xingrendong` to 行人洞 and `fengui` to 分规. Nothing was wrong
with the decoder: rime-ice, McBopomofo and CC-CEDICT have no 雪花冰 and no 杏仁凍, so the only
paths to them were 雪花 + a lone 冰 and 杏仁 + a lone 凍, and a lone character pays the backoff
penalty that exists precisely so that assembling text character by character loses to a word
-- here, to 宾馆 and 行人. 粉粿 was present, but only as `fen guo`, which is the dictionary
reading of 粿 and not how most people in Taiwan say it. The same gap is the tools/places story
for place names: scoring constants cannot fix a missing word, only the word list can.

WHAT A .src LINE SAYS
    traditional<TAB>simplified<TAB>reading[; reading...]

`simplified` is `=` when the two are spelled alike and `-` when no mainland entry should be
added. The Traditional form goes into the Taiwan model and the Simplified into the mainland one.

HOW A READING IS TRUSTED
A word under the wrong reading is unreachable by anyone who spells it right, so no reading is
taken on faith. Every syllable must be a reading its character has in McBopomofo's BPMFBase
(Taiwan) or rime-ice's 8105 table (mainland), *or* be listed in [EXTRA_READINGS] with the reason.
The one entry there is 粿 as `gui`: both tables read it only as `guo` (ㄍㄨㄛˇ, as the MOE
dictionary does), but the word comes from Taiwanese `kué` and is widely said and typed `gui`.
It is added beside `guo`, never instead of it -- the same both-readings rule 港墘 got in places.

Also rejected: a Traditional form containing a character OpenCC says is exclusively Simplified,
and any reading whose syllable the shipped dictionary does not already use (it would silently
fall out of `SyllableTable.kt`).

HOW MUCH A WORD WEIGHS
Its own count in the Taiwan corpus, exactly as `build_taipei_places.py` weighs a place name and
with its code: every occurrence of the string in PTT and the Traditional Common Crawl, put onto
phrase.occ's scale by the median-ratio calibration. 雪花冰 is then as common as its 330 PTT
sightings and its Common Crawl ones say, beside 雪花 and 宾馆 measured the same way.

The Simplified form gets the *same* measured count, because there is no mainland corpus here to
measure it in. That is an estimate, and it is the honest one: these are Taiwan foods, and a
mainland writer who names one is naming the same thing. Where rime-ice already weighs the
Simplified word (牛轧糖, 龟苓膏) the builder keeps the larger of the two, so a real mainland
measurement is never overwritten by this one.

Counting takes a few minutes and is cached in <work>/words/<name>_counts.tsv; it needs
pyahocorasick and pyarrow, as build_taipei_places.py does.
"""

from __future__ import annotations

import argparse
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bopomofo  # noqa: E402
from build_taipei_places import (  # noqa: E402
    CALIBRATION_MIN,
    calibration,
    corpus_counts,
    occurrences,
)

# Readings no reading table has, admitted by name. Each needs a reason a reviewer can check.
EXTRA_READINGS = {
    # Taiwanese kué; 粉粿 碗粿 草仔粿 are said and typed `gui` in Taiwan far more than `guo`.
    ("粿", "gui"),
}

MAX_WORD_LEN = 12  # build_pinyin_dict.MAX_WORD_LEN


def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


def char_readings(work: str) -> dict:
    """char -> every reading either model gives it: BPMFBase (Taiwan) and 8105 (mainland)."""
    out: dict[str, set] = defaultdict(set)
    for line in open(os.path.join(work, "BPMFBase.txt"), encoding="utf-8"):
        parts = line.split()
        if len(parts) >= 2 and not line.startswith("#") and len(parts[0]) == 1:
            syl = bopomofo.syllable(parts[1])
            if syl:
                out[parts[0]].add(syl)
    body = False
    for line in open(os.path.join(work, "8105.dict.yaml"), encoding="utf-8"):
        if not body:
            body = line.strip() == "..."
            continue
        parts = line.rstrip("\n").split("\t")
        if len(parts) >= 2 and len(parts[0]) == 1:
            out[parts[0]].add(parts[1])
    return out


def known_syllables(work: str) -> set:
    """Every syllable the mainland dictionary spells a word with -- the shipped table's set."""
    out: set = set()
    body = False
    for line in open(os.path.join(work, "base.dict.yaml"), encoding="utf-8"):
        if not body:
            body = line.strip() == "..."
            continue
        parts = line.split("\t")
        if len(parts) >= 2:
            out.update(parts[1].split())
    return out


def simplified_only(work: str) -> set:
    """Characters OpenCC maps away from themselves: never part of a Traditional word."""
    out = set()
    for line in open(os.path.join(work, "STCharacters.txt"), encoding="utf-8"):
        parts = line.strip().split("\t")
        if len(parts) >= 2 and parts[0] not in parts[1].split():
            out.add(parts[0])
    return out


def parse(path: str) -> list:
    """[(traditional, simplified or None, [[syllables], ...])] from a .src file."""
    rows = []
    for number, line in enumerate(open(path, encoding="utf-8"), 1):
        line = line.rstrip("\n")
        if not line.strip() or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 3:
            raise SystemExit(f"{path}:{number}: expected 3 tab-separated fields")
        trad, simp, readings = parts
        simp = trad if simp == "=" else None if simp == "-" else simp
        rows.append((trad, simp, [r.split() for r in readings.split(";")], number))
    return rows


def validate(path: str, rows: list, chars: dict, known: set, simp_only: set) -> None:
    errors = []
    for trad, simp, readings, number in rows:
        where = f"{path}:{number}: {trad}"
        if len(trad) > MAX_WORD_LEN:
            errors.append(f"{where} is longer than {MAX_WORD_LEN}")
        if simp is not None and len(simp) != len(trad):
            errors.append(f"{where} and {simp} differ in length")
        bad = [c for c in trad if c in simp_only]
        if bad:
            errors.append(f"{where} has Simplified-only characters {bad}")
        for syls in readings:
            if len(syls) != len(trad):
                errors.append(f"{where} does not align with {' '.join(syls)}")
                continue
            for i, s in enumerate(syls):
                if s not in known:
                    errors.append(f"{where}: `{s}` is not a syllable the dictionary uses")
                forms = {trad[i]} | ({simp[i]} if simp else set())
                if not any(s in chars.get(c, ()) or (c, s) in EXTRA_READINGS for c in forms):
                    errors.append(f"{where}: no reading table gives {trad[i]} as `{s}`")
    if errors:
        raise SystemExit("\n".join(errors))


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("src", help="a tools/words/<name>.src file")
    ap.add_argument("--work", default="../zhwork",
                    help="directory holding the upstream sources and corpus (see build_pinyin_dict)")
    ap.add_argument("--out", help="defaults to the .src path with a .tsv extension")
    args = ap.parse_args()
    out_path = args.out or os.path.splitext(args.src)[0] + ".tsv"
    name = os.path.splitext(os.path.basename(args.src))[0]

    rows = parse(args.src)
    validate(args.src, rows, char_readings(args.work), known_syllables(args.work),
             simplified_only(args.work))
    log(f"{args.src}: {len(rows)} words, readings valid")

    occ = occurrences(os.path.join(args.work, "phrase.occ"))
    calibrators = {w for w, n in occ.items() if len(w) >= 2 and n >= CALIBRATION_MIN}
    counts, corpus_chars = corpus_counts(
        args.work, {t for t, *_ in rows} | calibrators,
        cache=os.path.join(args.work, "words", f"{name}_counts.tsv"),
    )
    factor, used = calibration(occ, counts)
    log(f"corpus: {corpus_chars / 1e9:.2f}G characters; 1 occurrence = {factor:.4f} phrase.occ "
        f"counts (median over {used} words)")

    with open(out_path, "w", encoding="utf-8") as f:
        f.write(f"# Generated by tools/build_word_list.py from {os.path.basename(args.src)} "
                "-- edit that, not this.\n")
        f.write("# word\treading\tmodel\tcorpus count, rescaled onto phrase.occ\n")
        n = 0
        for trad, simp, readings, _ in rows:
            weight = f"{counts.get(trad, 0) * factor:.2f}"
            for syls in readings:
                reading = " ".join(syls)
                f.write(f"{trad}\t{reading}\ttw\t{weight}\n")
                n += 1
                if simp is not None:
                    f.write(f"{simp}\t{reading}\tcn\t{weight}\n")
                    n += 1
            log(f"  {trad:<8} {counts.get(trad, 0):>7} occurrences  -> {weight}")
    log(f"wrote {out_path}: {n} entries")


if __name__ == "__main__":
    main()
