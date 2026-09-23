#!/usr/bin/env python3
"""
Shows the suggestion bar for a query, as the app would rank it.

This is a *model* of the app's ranking, not the app itself: it reads the same two assets and
reimplements UnifiedCandidates and the decoder's scoring closely enough to answer the only
question that matters before a build -- what actually shows up, and why.

    tools/rank_preview.py niuroumian ezijian ha happy
    tools/rank_preview.py --traditional niuroumian

Print the scores and the ordering stops being a matter of opinion.
"""

from __future__ import annotations

import argparse
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dump_pinyin_dict import Reader  # noqa: E402

# Per-corpus totals, measured by tools/corpus_totals.py and mirrored in ScriptMode.kt. A weight
# only means something against its own corpus: the mainland column is 2.5x the Taiwan one, so
# dividing both by one number is a thumb on the scale worth ~0.9 nats.
CN_TOTAL = 5.629e9
TW_TOTAL = 2.280e9
BACKOFF = 6.0
FUZZY_PENALTY = 2.3
EMOJI_TOKEN_SHARE = 1 / 300.0
EMOJI_HARMONIC = 8.1
COVERAGE_PENALTY = 18.0
CHINESE_GATE = 7.0
NOT_ENGLISH = -14.0
ENGLISH_EVIDENCE = 1.0

# lexicon_en.tsv stores round(100 * log10(count)); this turns one back into ln P(word).
LEXICON_CORPUS = 3.818e11


def load_lexicon(path: str) -> dict:
    out = {}
    for line in open(path, encoding="utf-8"):
        word, _, score = line.rstrip("\n").partition("\t")
        if not score:
            continue
        try:
            out[word.lower()] = math.log((10 ** (int(score) / 100.0)) / LEXICON_CORPUS)
        except ValueError:
            continue
    return out


def unigram(weight: int, total: float = CN_TOTAL) -> float:
    return math.log((weight + 1) / total)


def log_prob(cn: int, tw: int, mode: str):
    """
    The best log-probability a mode assigns, or None when no corpus it reads has the entry.

    Mirrors ScriptMode.logProbOf: each column is normalised by its *own* total and the best wins.
    Summing the columns would both invent a frequency no corpus observed and double-count the
    characters common to both.
    """
    best = None
    if mode in ("cn", "both") and cn > 0:
        best = unigram(cn, CN_TOTAL)
    if mode in ("tw", "both") and tw > 0:
        cand = unigram(tw, TW_TOTAL)
        if best is None or cand > best:
            best = cand
    return best


def rank_weight(cn: int, tw: int, mode: str) -> int:
    """Mirrors ScriptMode.rankWeightOf: Taiwan weights scaled onto the mainland corpus."""
    best = 0
    if mode in ("cn", "both") and cn > 0:
        best = cn
    if mode in ("tw", "both") and tw > 0:
        scaled = int(tw * (CN_TOTAL / TW_TOTAL))
        if scaled > best:
            best = scaled
    return best


def emoji_score(rank: int, query: str) -> float:
    if len(query) <= 1:
        bonus = -1.2
    elif len(query) == 2:
        bonus = -0.5
    else:
        bonus = 0.0
    return (math.log(EMOJI_TOKEN_SHARE) - math.log(rank + 1)
            - math.log(EMOJI_HARMONIC) + bonus)


# --- emoji index ------------------------------------------------------------------------------


class Emoji:
    def __init__(self, path: str) -> None:
        self.entries = []
        self.names = {}
        self.terms = {}
        for line in open(path, encoding="utf-8"):
            line = line.rstrip("\n")
            if not line:
                continue
            parts = line.split("\t")
            if len(parts) < 2:
                continue
            i = len(self.entries)
            self.entries.append((parts[0], parts[1]))
            self.names.setdefault(parts[1], i)
            terms = (parts[2].split("|") if len(parts) > 2 else []) + [parts[1]]
            for t in terms:
                if t:
                    self.terms.setdefault(t, []).append(i)

    def search(self, query: str, limit: int = 12):
        """Mirrors EmojiIndex.search: exact name, exact term, term prefix, name prefix."""
        q = query.lower().strip()
        if not q:
            return []
        ranked = {}

        def offer(i, tier):
            if i not in ranked or tier < ranked[i]:
                ranked[i] = tier

        if q in self.names:
            offer(self.names[q], 0)
        for i in self.terms.get(q, []):
            offer(i, 1)
        for term, idxs in self.terms.items():
            if term != q and term.startswith(q):
                for i in idxs:
                    offer(i, 2)
        for name, i in self.names.items():
            if name.startswith(q):
                offer(i, 3)
        order = sorted(ranked.items(), key=lambda kv: (kv[1], kv[0]))[:limit]
        return [self.entries[i][0] for i, _ in order]


# --- pinyin segmentation ----------------------------------------------------------------------


def readings(letters: str, syllables: set, max_readings: int = 16):
    """Every way to cut `letters` into known syllables, longest-first per path."""
    out = []

    def walk(pos: int, acc: list, ends: list):
        if len(out) >= max_readings:
            return
        if pos == len(letters):
            out.append((list(acc), list(ends)))
            return
        for end in range(min(len(letters), pos + 6), pos, -1):
            piece = letters[pos:end]
            if piece in syllables:
                acc.append(piece)
                ends.append(end)
                walk(end, acc, ends)
                acc.pop()
                ends.pop()

    walk(0, [], [])
    return out


def decode(letters: str, reader: Reader, words: dict, chars: dict, mode: str,
           limit: int = 12):
    """
    A small Viterbi over the same lattice the app builds, returning (text, score, consumed).

    Beam of 4, single-character backoff edges, the same unigram scoring. Enough to reproduce the
    app's ordering for the short inputs a suggestion bar deals with.
    """
    syls = set(reader.syllables)
    # In the three phases Decoder.candidates uses, so the order printed is the order shown:
    # whole-input readings, then prefix words longest first, then single characters.
    full: dict = {}
    prefix: dict = {}
    floor: dict = {}
    results = full

    for ids, ends in readings(letters, syls):
        n = len(ids)
        best = {0: [("", 0.0)]}
        for i in range(n):
            if i not in best:
                continue
            for span in range(1, min(12, n - i) + 1):
                key = " ".join(ids[i:i + span])
                entries = words.get(key, [])
                cands = []
                for w, wt in entries:
                    lp = log_prob(wt[0], wt[1], mode)
                    if lp is not None:
                        cands.append((w, lp, False))
                if span == 1:
                    for ch, wt in chars.get(ids[i], [])[:12]:
                        lp = log_prob(wt[0], wt[1], mode)
                        if lp is not None and len(ch) == 1:
                            cands.append((ch, lp, True))
                for text, lp, backoff in cands:
                    if len(text) != span:
                        continue
                    # Spelling one syllable with one character is a backoff however the entry was
                    # found. The Taiwan model keeps single-character frequencies in the *word*
                    # index (是, 時, 十 all sit under `shi`), so those arrive as ordinary words
                    # carrying no penalty; the unpenalised copy then wins and the bar fills with
                    # character pairs like 是大 that no corpus contains. Mirrors Decoder.wordsFor.
                    if span == 1 and len(text) == 1:
                        backoff = True
                    sc = lp - (BACKOFF if backoff else 0.0)
                    tgt = i + span
                    for prev_text, prev_score in best.get(i, []):
                        entry = (prev_text + text, prev_score + sc)
                        lst = best.setdefault(tgt, [])
                        lst.append(entry)
                        lst.sort(key=lambda t: -t[1])
                        del lst[4:]
        # Full-length decodings.
        for text, score in best.get(n, []):
            consumed = len(letters)
            if text not in results or score > results[text][0]:
                results[text] = (score, consumed)
    first = next(iter(readings(letters, syls)), None)
    if first is not None:
        ids, ends = first
        n = len(ids)
        # Prefix words of the best reading, longest first: committing one word of a longer
        # phrase is normal, and a longer prefix is always offered before a shorter one.
        for span in range(min(n, 12), 0, -1):
            if span == n:
                continue
            key = " ".join(ids[:span])
            ws = []
            for w, wt in words.get(key, []):
                score = log_prob(wt[0], wt[1], mode)
                if score is not None and len(w) == span:
                    ws.append((w, score))
            for w, score in ws[:6]:
                prefix.setdefault(w, (score, ends[span - 1]))
        # Character floor for the first syllable.
        for ch, wt in chars.get(ids[0], [])[:8]:
            score = log_prob(wt[0], wt[1], mode)
            if score is None or len(ch) != 1:
                continue
            floor.setdefault(ch, (score, ends[0]))

    # No re-sort across phases: that is what let 他的 beat 他的爸爸很讨厌我. Whole-input
    # readings by score, capped like FULL_DECODINGS; the other phases in the order produced.
    out, seen = [], set()
    phases = [sorted(full.items(), key=lambda kv: -kv[1][0])[:6],
              list(prefix.items()), list(floor.items())]
    for phase in phases:
        for text, (score, consumed) in phase:
            if text not in seen:
                seen.add(text)
                out.append((text, score, consumed))
    return out[:limit]


def load_tables(reader: Reader):
    words: dict = {}
    for key, offsets in reader.keys():
        words[key] = [(w, (cn, tw)) for w, cn, tw in (reader.entry(o) for o in offsets)]
    chars: dict = {}
    for syl, items in reader.chars():
        chars[syl] = [(ch, (cn, tw)) for ch, cn, tw in items]
    return words, chars


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("queries", nargs="+")
    ap.add_argument("--dict", default="app/src/main/assets/pinyin.bin")
    ap.add_argument("--emoji", default="app/src/main/assets/emoji_en.tsv")
    ap.add_argument("--lexicon", default="app/src/main/assets/lexicon_en.tsv")
    ap.add_argument("--traditional", action="store_true",
                    help="score against the Taiwan model instead of the mainland one")
    ap.add_argument("--both", action="store_true",
                    help="rank both scripts against each other, as the English bar does")
    ap.add_argument("--limit", type=int, default=10)
    args = ap.parse_args()

    reader = Reader(args.dict)
    words, chars = load_tables(reader)
    emoji = Emoji(args.emoji)
    lexicon = load_lexicon(args.lexicon)

    for query in args.queries:
        q = query.lower()
        rows = []
        english = lexicon.get(q, NOT_ENGLISH)
        penalty = max(english - NOT_ENGLISH, 0.0) * ENGLISH_EVIDENCE
        emoji_rows = [(emoji_score(rank, q), "emoji", e, len(q))
                      for rank, e in enumerate(emoji.search(q))]
        mode = "both" if args.both else ("tw" if args.traditional else "cn")
        # Mirrors UnifiedCandidates.rank: Chinese keeps decoder order, its sort key clamped to
        # never exceed the previous one, and emoji are merged in around it.
        zh_rows, ceiling = [], math.inf
        for text, score, consumed in decode(q, reader, words, chars, mode, limit=20):
            coverage = consumed / len(q) if q else 0.0
            key = min(ceiling, score - (1 - coverage) * COVERAGE_PENALTY - penalty)
            ceiling = key
            zh_rows.append((key, "chinese", text, consumed))
        if zh_rows and emoji_rows and zh_rows[0][0] < emoji_rows[0][0] - CHINESE_GATE:
            zh_rows = []
        rows, i, j = [], 0, 0
        while i < len(emoji_rows) or j < len(zh_rows):
            if j >= len(zh_rows) or (i < len(emoji_rows) and emoji_rows[i][0] >= zh_rows[j][0]):
                rows.append(emoji_rows[i]); i += 1
            else:
                rows.append(zh_rows[j]); j += 1

        model = {"both": "both scripts", "tw": "taiwan", "cn": "mainland"}[mode]
        note = (f"english lnP {english:.2f}, chinese charged {penalty:.2f}"
                if penalty > 0 else "not an english word")
        print(f"\n=== {query}   ({model} model; {note}) ===")
        if not rows:
            print("  (nothing)")
        for score, kind, text, consumed in rows[:args.limit]:
            print(f"  {score:9.2f}  {kind:8} {text}   (consumes {consumed}/{len(q)})")


if __name__ == "__main__":
    main()
