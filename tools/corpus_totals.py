#!/usr/bin/env python3
"""
Sums each weight column in a built pinyin.bin.

These two totals are the denominators cross-script ranking rests on: a weight only means
something relative to its own corpus, and the two corpora here differ by ~2.5x in size. The
figures are pasted into `ScriptMode.CN_TOTAL` / `TW_TOTAL`; run this after rebuilding the
dictionary and update them if they have moved.

    tools/corpus_totals.py
    tools/corpus_totals.py --dict app/src/main/assets/pinyin.bin
"""

from __future__ import annotations

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dump_pinyin_dict import Reader  # noqa: E402


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dict", default="app/src/main/assets/pinyin.bin")
    args = ap.parse_args()

    reader = Reader(args.dict)

    cn_total = tw_total = 0
    cn_count = tw_count = 0
    both = 0
    for _key, offsets in reader.keys():
        for offset in offsets:
            _word, cn, tw = reader.entry(offset)
            if cn > 0:
                cn_total += cn
                cn_count += 1
            if tw > 0:
                tw_total += tw
                tw_count += 1
            if cn > 0 and tw > 0:
                both += 1

    # Characters live in a separate section and are scored by the same denominators, so they
    # belong in the totals the decoder divides by.
    ccn_total = ctw_total = 0
    for _syllable, items in reader.chars():
        for _text, cn, tw in items:
            ccn_total += cn
            ctw_total += tw

    print(f"words   cn: {cn_count:>8,} entries  total {cn_total:>15,}")
    print(f"        tw: {tw_count:>8,} entries  total {tw_total:>15,}")
    print(f"        in both columns: {both:,}")
    print(f"chars   cn: total {ccn_total:>15,}")
    print(f"        tw: total {ctw_total:>15,}")
    print()
    print(f"ratio cn/tw (words): {cn_total / tw_total:.3f}")
    print()
    print("paste into ScriptMode:")
    print(f"        const val CN_TOTAL = {cn_total / 1e9:.3f}e9f")
    print(f"        const val TW_TOTAL = {tw_total / 1e9:.3f}e9f")


if __name__ == "__main__":
    main()
