#!/usr/bin/env python3
"""
Builds the Chinese pinyin dictionary asset from upstream sources.

Run this when the upstream data changes; the output is committed, so a normal build never
needs it. Same shape as build_lexicon.py: download into a scratch directory, compile, and
write one binary asset the app mmaps out of its APK.

    tools/build_pinyin_dict.py --work zhwork --out app/src/main/assets/pinyin.bin

WHAT GOES IN
  rime-ice base.dict.yaml   543k words: word, space-separated pinyin, usage weight   GPL-3.0
  rime-ice ext.dict.yaml    339k more, unweighted (names, places, terms)             GPL-3.0
  rime-ice 8105.dict.yaml   per-character readings and frequency                     GPL-3.0
  OpenCC ST/TW tables       Simplified -> Taiwan Traditional, phrase-aware           Apache-2.0
  McBopomofo BPMFMappings   140k Traditional words with Taiwan bopomofo readings     MIT
  McBopomofo phrase.occ     Taiwan corpus frequencies for those words                MIT
  McBopomofo BPMFBase       per-character Taiwan readings                            MIT

WHY TWO LANGUAGE MODELS
The rime-ice data is mainland Simplified. Converting it to Traditional produces *Simplified
Chinese written in Traditional characters*, which is not what a Taiwanese user types: rime-ice
has no 蚵仔煎 at all (it explicitly normalises it to the mainland 蚝仔煎 read `hao zai jian`),
and its frequencies are mainland ones. So Taiwan is not a skin over the Simplified dictionary --
it is its own model, keyed by its own readings and weighted by its own corpus, and the two are
merged here into one syllable index carrying a weight per region.

That is what makes 牛肉麵 outrank 牛肉面 for a Taiwan user while the mainland user still gets
牛肉面 first: the same key `niu rou mian` holds both, and the decoder reads the weight for the
region in play rather than converting one into the other.

The rime-ice data is GPL-3.0 and that licence reaches the app if it is ever distributed.
See NOTES.md; this is a deliberate, recorded choice. McBopomofo's data is MIT.

WHY A BINARY ASSET
Parsing 880k text lines at startup would cost seconds and a large heap. The output here is
read with one mmap and binary-searched in place: syllable keys sorted so a prefix is a
contiguous range, payloads varint-packed. Nothing is decoded until a lookup asks for it.

The entire file is little-endian. Layout:

    magic "PYD2"  u32 version  u32 sectionCount
    section table: for each, u32 id, u32 offset, u32 length
    sections follow, each 8-byte aligned

Sections: SYLLABLES, ENTRIES, INDEX, CHARS, BIGRAM, ST_CHARS, ST_PHRASES.
"""

from __future__ import annotations

import argparse
import os
import struct
import sys
import urllib.request
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bopomofo  # noqa: E402  (path set above so this runs from any directory)

# --- sources ---------------------------------------------------------------------------------

RIME_ICE = "https://raw.githubusercontent.com/iDvel/rime-ice/main/cn_dicts/"
OPENCC = "https://raw.githubusercontent.com/BYVoid/OpenCC/master/data/dictionary/"
MCBPMF = "https://raw.githubusercontent.com/openvanilla/McBopomofo/master/Source/Data/"

SOURCES = {
    "base.dict.yaml": RIME_ICE + "base.dict.yaml",
    "ext.dict.yaml": RIME_ICE + "ext.dict.yaml",
    "8105.dict.yaml": RIME_ICE + "8105.dict.yaml",
    "STCharacters.txt": OPENCC + "STCharacters.txt",
    "STPhrases.txt": OPENCC + "STPhrases.txt",
    "TWVariants.txt": OPENCC + "TWVariants.txt",
    "TWPhrases.txt": OPENCC + "TWPhrases.txt",
    "BPMFMappings.txt": MCBPMF + "BPMFMappings.txt",
    "BPMFBase.txt": MCBPMF + "BPMFBase.txt",
    "phrase.occ": MCBPMF + "phrase.occ",
}

# Section ids. Stable numbers: the reader switches on these, so they may be added to but not
# renumbered.
SEC_SYLLABLES = 1
SEC_ENTRIES = 2
SEC_INDEX = 3
SEC_CHARS = 4
SEC_BIGRAM = 5
SEC_ST_CHARS = 6
SEC_ST_PHRASES = 7

MAGIC = b"PYD3"
VERSION = 3

# The two language models an entry can be weighted under. An entry carries a weight for each,
# and either may be zero: 蚵仔煎 exists only in TW, 蚝仔煎 only in CN, 台灣/台湾 in both under
# different spellings. Zero means "this model does not have this word" and the decoder skips it,
# which is what keeps mainland words out of a Taiwan user's candidates and vice versa.
REGION_CN = 0
REGION_TW = 1

# Taiwan corpus total, from phrase.occ. Weights from the two corpora are not comparable as raw
# counts -- rime-ice's run to 19M on a ~2e9 corpus, McBopomofo's to 615k on a 22.6M one -- so
# the TW weights are rescaled onto the CN corpus's scale before being written. Ranking then
# compares like with like, and the app's single CORPUS_TOTAL stays correct for both.
TW_CORPUS_TOTAL = 22_601_692
CN_CORPUS_TOTAL = 2_000_000_000

# How many candidates any one syllable key may keep. A key like "yi" has hundreds of words and
# the tail is never shown -- the strip holds a handful and a user who scrolls past 50 is not
# helped by 500. Bounded here so lookup cost does not depend on how common the key is.
MAX_PER_KEY = 60

# Words longer than this are dropped. The decoder composes long input from shorter units, so a
# 12-character idiom entry earns its size only if it is genuinely fixed.
MAX_WORD_LEN = 12


def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


def fetch(work: str, name: str, url: str) -> str:
    """Downloads once and caches; the scratch directory is not cleaned between runs."""
    path = os.path.join(work, name)
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return path
    log(f"  fetching {name}")
    os.makedirs(work, exist_ok=True)
    with urllib.request.urlopen(url, timeout=120) as r, open(path, "wb") as f:
        f.write(r.read())
    return path


# --- parsing ---------------------------------------------------------------------------------


def rime_entries(path: str):
    """
    Yields (word, [syllables], weight) from a Rime dictionary.

    Rime files carry a YAML header terminated by '...', after which every non-comment line is
    tab-separated. Splitting on tabs rather than whitespace matters: the pinyin column is itself
    space-separated, so a whitespace split would scatter it across fields.
    """
    with open(path, encoding="utf-8") as f:
        in_body = False
        for line in f:
            line = line.rstrip("\n").rstrip("\r")
            if not in_body:
                if line.strip() == "...":
                    in_body = True
                continue
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 2:
                continue
            word, pinyin = parts[0].strip(), parts[1].strip()
            if not word or not pinyin:
                continue
            # ext.dict has no weight column; those entries are real words that simply lack a
            # frequency, so they get 1 rather than being dropped.
            try:
                weight = int(parts[2]) if len(parts) > 2 and parts[2].strip() else 1
            except ValueError:
                weight = 1
            yield word, pinyin.split(), max(weight, 0)


def mcbopomofo_frequencies(path: str) -> dict:
    """
    Reads `phrase.occ`: one `word count` pair per line, space-separated.

    These are occurrence counts from a Taiwan corpus and are the entire reason the Taiwan model
    ranks like Taiwan rather than like a converted mainland dictionary.
    """
    out: dict[str, int] = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.split()
            if len(parts) < 2:
                continue
            try:
                count = int(parts[1])
            except ValueError:
                continue
            if count > 0:
                out[parts[0]] = max(out.get(parts[0], 0), count)
    return out


def mcbopomofo_entries(path: str, base_path: str, freqs: dict, stats: dict):
    """
    Yields (word, [pinyin syllables], tw_weight) for the Taiwan model.

    Readings are bopomofo and are converted to this dictionary's pinyin spellings by
    `tools/bopomofo.py`. A word whose reading will not convert is counted in [stats] and
    dropped rather than guessed at -- see that module for why silence would be worse.

    A word with no corpus count still enters at weight 1: it is a real Traditional word that
    merely went unseen, and dropping it would make the Taiwan model *smaller* than the
    Simplified one it is meant to replace for those users.
    """
    for path_name, is_base in ((base_path, True), (path, False)):
        with open(path_name, encoding="utf-8") as f:
            for line in f:
                if line.startswith("#"):
                    continue
                parts = line.split()
                # BPMFBase is `char bopomofo pinyin tone tag`; only the first two matter, and a
                # single character has exactly one reading symbol.
                if is_base:
                    if len(parts) < 2:
                        continue
                    word, readings = parts[0], parts[1:2]
                else:
                    if len(parts) < 2:
                        continue
                    word, readings = parts[0], parts[1:]
                if not word or len(word) > MAX_WORD_LEN:
                    continue
                # One reading symbol per character, as the decoder maps them positionally.
                if len(readings) != len(word):
                    stats["misaligned"] = stats.get("misaligned", 0) + 1
                    continue
                syls = bopomofo.reading(readings)
                if syls is None:
                    stats["unconvertible"] = stats.get("unconvertible", 0) + 1
                    continue
                yield word, syls, max(freqs.get(word, 0), 1)


def opencc_table(path: str) -> dict:
    """
    Reads an OpenCC table: key<TAB>value(s), where multiple values are space-separated
    alternatives in preference order. Only the first is kept -- conversion has to be
    deterministic, and OpenCC puts the standard form first.
    """
    out = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 2:
                continue
            key, values = parts[0], parts[1].split()
            if key and values:
                out[key] = values[0]
    return out


# --- binary writing --------------------------------------------------------------------------


class Blob:
    """A growable little-endian byte buffer with the varint forms the reader expects."""

    def __init__(self) -> None:
        self.buf = bytearray()

    def u8(self, v: int) -> None:
        self.buf.append(v & 0xFF)

    def u16(self, v: int) -> None:
        self.buf += struct.pack("<H", v)

    def u32(self, v: int) -> None:
        self.buf += struct.pack("<I", v)

    def varint(self, v: int) -> None:
        """LEB128. Weights span 0..19M and most are small, so a fixed u32 would waste 3 bytes."""
        assert v >= 0
        while True:
            b = v & 0x7F
            v >>= 7
            if v:
                self.buf.append(b | 0x80)
            else:
                self.buf.append(b)
                break

    def utf8(self, s: str) -> None:
        data = s.encode("utf-8")
        self.varint(len(data))
        self.buf += data

    def __len__(self) -> int:
        return len(self.buf)


def build(work: str, out_path: str, max_entries: int) -> None:
    log("sources")
    paths = {name: fetch(work, name, url) for name, url in SOURCES.items()}

    # --- words ---------------------------------------------------------------------------
    # Keyed by (word, syllables) so the same word with two readings stays two entries -- the
    # whole point of a pinyin index. base wins over ext on weight because it is the measured one.
    log("reading dictionaries")
    # Each value is a [cn, tw] pair: the word's weight under each language model. Keeping both
    # on one key is what lets the decoder rank for a region without a second dictionary, and
    # what lets a word present in only one region simply carry a zero for the other.
    words: dict[tuple, list] = {}

    def offer(key: tuple, weight: int, region: int) -> None:
        slot = words.get(key)
        if slot is None:
            slot = [0, 0]
            words[key] = slot
        if weight > slot[region]:
            slot[region] = weight

    for name in ("base.dict.yaml", "ext.dict.yaml"):
        n = 0
        for word, syls, weight in rime_entries(paths[name]):
            if len(word) > MAX_WORD_LEN or not syls:
                continue
            # Entries whose pinyin does not have one syllable per character are malformed for
            # our purposes: the decoder maps syllables to characters positionally.
            if len(syls) != len(word):
                continue
            offer((word, " ".join(syls)), weight, REGION_CN)
            n += 1
        log(f"  {name}: {n} usable")

    # --- the Taiwan model ------------------------------------------------------------------
    # Native Traditional words with Taiwan readings and Taiwan corpus frequencies, rescaled onto
    # the mainland corpus's scale so one CORPUS_TOTAL normalises both (see TW_CORPUS_TOTAL).
    tw_freqs = mcbopomofo_frequencies(paths["phrase.occ"])
    scale = CN_CORPUS_TOTAL / TW_CORPUS_TOTAL
    stats: dict = {}
    n_tw = 0
    for word, syls, weight in mcbopomofo_entries(
        paths["BPMFMappings.txt"], paths["BPMFBase.txt"], tw_freqs, stats
    ):
        offer((word, " ".join(syls)), max(int(weight * scale), 1), REGION_TW)
        n_tw += 1
    log(f"  McBopomofo: {n_tw} usable "
        f"({stats.get('unconvertible', 0)} unconvertible readings, "
        f"{stats.get('misaligned', 0)} misaligned)")
    log(f"  words: {len(words)} keyed entries "
        f"({sum(1 for v in words.values() if v[REGION_TW])} in the Taiwan model, "
        f"{sum(1 for v in words.values() if v[REGION_CN])} in the mainland one)")

    # --- characters ----------------------------------------------------------------------
    # Single characters get their own section: the decoder needs a guaranteed fallback for any
    # syllable, including ones no multi-character word covers.
    # Per region, for the same reason words are: the character a Taiwan user expects for a
    # syllable is often not the mainland one (臺 vs 台, 麵 vs 面), and a single shared list
    # would put the wrong one at the front for one of them.
    chars: dict[tuple, list] = {}

    def offer_char(key: tuple, weight: int, region: int) -> None:
        slot = chars.get(key)
        if slot is None:
            slot = [0, 0]
            chars[key] = slot
        if weight > slot[region]:
            slot[region] = weight

    for word, syls, weight in rime_entries(paths["8105.dict.yaml"]):
        if len(word) == 1 and len(syls) == 1:
            offer_char((word, syls[0]), weight, REGION_CN)
    # Single-character entries in the word tables carry better frequencies than the per-character
    # tables alone -- and this is where the Taiwan characters arrive, from BPMFBase.
    for (word, syl), weight in words.items():
        if len(word) == 1:
            if weight[REGION_CN]:
                offer_char((word, syl), weight[REGION_CN], REGION_CN)
            if weight[REGION_TW]:
                offer_char((word, syl), weight[REGION_TW], REGION_TW)
    log(f"  characters: {len(chars)} "
        f"({sum(1 for v in chars.values() if v[REGION_TW])} in the Taiwan model)")

    # --- prune ---------------------------------------------------------------------------
    # Keep the strongest entries per syllable key. Sorting by weight before truncating is what
    # makes the cut safe: what is dropped is always the tail nobody would have scrolled to.
    #
    # Ranked by the *stronger* of an entry's two weights, never by their sum. A Taiwan-only word
    # has a mainland weight of zero, and averaging would push it off the end of its own key --
    # 蚵仔煎 would be dropped for being unknown in China, which is precisely the failure this
    # whole exercise exists to fix.
    by_key: dict[str, list] = defaultdict(list)
    for (word, syls), weight in words.items():
        by_key[syls].append((max(weight), word, weight))

    kept: list[tuple[str, str, list]] = []
    for syls, items in by_key.items():
        items.sort(key=lambda t: (-t[0], t[1]))
        for _rank, word, weight in items[:MAX_PER_KEY]:
            kept.append((syls, word, weight))

    # A global cap keeps the asset to a predictable size. Applied by weight across the whole
    # set, after the per-key cap, so common words survive regardless of which key they sit on.
    if max_entries and len(kept) > max_entries:
        kept.sort(key=lambda t: -max(t[2]))
        kept = kept[:max_entries]
        log(f"  capped to {max_entries} entries")

    kept.sort(key=lambda t: (t[0], -max(t[2]), t[1]))
    log(f"  entries: {len(kept)}")

    # --- syllable table ------------------------------------------------------------------
    # Syllables are referenced by index, not spelled out, in the index section. There are only
    # a few hundred, so one byte-ish varint replaces a 2-6 byte string in every key.
    syllables = sorted({s for syls, _, _ in kept for s in syls.split()} |
                       {syl for (_, syl) in chars})
    syl_id = {s: i for i, s in enumerate(syllables)}
    log(f"  syllables: {len(syllables)}")

    # --- bigrams -------------------------------------------------------------------------
    # Context for the Viterbi pass: how often word B follows word A. There is no corpus here,
    # so this is derived from the weighted phrase table -- a multi-character word is itself
    # evidence that its parts adjoin. Coarse, but it is what separates 他/她/它 by neighbour
    # rather than by raw frequency, and it costs nothing extra to ship.
    #
    # Both models contribute, on the common scale the TW rescaling established. The pairs are
    # mostly script-disjoint anyway -- 麵 only ever follows 牛肉 in the Taiwan data and 面 only
    # in the mainland data -- so one table serves both without the regions contaminating each
    # other's context.
    log("deriving bigrams")
    bigram: dict[tuple, int] = defaultdict(int)
    for (word, _syls), weight in words.items():
        total = weight[REGION_CN] + weight[REGION_TW]
        if len(word) < 2 or total <= 0:
            continue
        for i in range(len(word) - 1):
            bigram[(word[i], word[i + 1])] += total
    # Only the strong pairs are worth their bytes; the long tail adds size without changing
    # any ranking decision. 150k covers the pairs that actually arbitrate a homophone choice --
    # past that the counts are so small the decoder's smoothing floor dominates them anyway.
    pairs = sorted(bigram.items(), key=lambda kv: -kv[1])[:150_000]
    pairs.sort(key=lambda kv: kv[0])
    log(f"  bigrams: {len(pairs)}")

    # --- conversion ----------------------------------------------------------------------
    # Simplified -> Taiwan Traditional, following OpenCC's own s2twp chain.
    #
    # The order is the whole subtlety, and getting it backwards is silent: the TW tables are
    # keyed on *Traditional*, so they are a second pass over the output of the first, not an
    # alternative to it. 软件 is the case that proves it -- S->T alone gives 軟件, and only then
    # does TWPhrases rewrite that to the Taiwan word 軟體. Applying TWPhrases to the Simplified
    # text matches nothing at all.
    #
    # Both passes are pre-composed here into one Simplified-keyed table so the app converts with
    # a single longest-match walk instead of running a two-stage chain on every keystroke.
    st_chars = opencc_table(paths["STCharacters.txt"])
    st_phrases = opencc_table(paths["STPhrases.txt"])
    tw_variants = opencc_table(paths["TWVariants.txt"])
    tw_phrases = opencc_table(paths["TWPhrases.txt"])

    def taiwanise(traditional: str) -> str:
        """The second stage: Traditional -> Taiwan Traditional, phrases before variants."""
        for src, dst in tw_phrases.items():
            if src in traditional:
                traditional = traditional.replace(src, dst)
        return "".join(tw_variants.get(c, c) for c in traditional)

    # Stage one applied to every key, then stage two on its result.
    composed_chars = {k: taiwanise(v) for k, v in st_chars.items()}
    composed_phrases = {k: taiwanise(v) for k, v in st_phrases.items()}

    # A Simplified phrase whose Taiwan form differs from converting it character by character
    # has to be in the phrase table, because the character pass alone cannot produce it. This
    # is what pulls 软件 -> 軟體 in: 软件 is not an STPhrases key, so it only becomes a phrase
    # entry once the TW stage has made it differ from 軟件.
    # One reverse character map, built once. Where several Simplified characters converge on the
    # same Traditional one, the first in sorted order wins: it only has to round-trip well
    # enough to produce a Simplified key, and the forward tables above remain authoritative.
    trad_to_simp: dict[str, str] = {}
    for simp, trad in sorted(st_chars.items()):
        trad_to_simp.setdefault(trad, simp)

    extra = 0
    for trad_phrase, trad in tw_phrases.items():
        # Map the Traditional key back to Simplified so the table stays Simplified-keyed.
        simp_key = "".join(trad_to_simp.get(c, c) for c in trad_phrase)
        if simp_key and simp_key not in composed_phrases:
            composed_phrases[simp_key] = taiwanise(trad)
            extra += 1

    # Finally drop phrases the character pass already reproduces exactly -- they cost bytes and
    # change nothing.
    st_phrases = {
        k: v for k, v in composed_phrases.items()
        if v != "".join(composed_chars.get(c, c) for c in k)
    }
    st_chars = composed_chars
    log(f"  conversion: {len(st_chars)} chars, {len(st_phrases)} phrases "
        f"({extra} from Taiwan vocabulary)")

    # --- sections ------------------------------------------------------------------------
    log("writing")

    syl_sec = Blob()
    syl_sec.u32(len(syllables))
    for s in syllables:
        syl_sec.utf8(s)

    # Entries: the words themselves, in the order the index will point at.
    #
    # The offsets recorded here are relative to the START OF THE SECTION, so they already
    # include the u32 count below -- a reader seeks to section_start + offset and must not add
    # the header again. Writing it this way (rather than relative to the first entry) means the
    # reader never needs to know the header's width.
    # Each entry now carries both weights, mainland first. Two varints rather than one: the
    # common case is a word in a single model, whose other weight is 0 and costs one byte.
    entries = Blob()
    entries.u32(len(kept))
    entry_offsets = []
    for syls, word, weight in kept:
        entry_offsets.append(len(entries))
        entries.utf8(word)
        entries.varint(weight[REGION_CN])
        entries.varint(weight[REGION_TW])

    # Index: syllable-key -> the entries under it. Keys are sorted by their syllable-id
    # sequence, so every prefix of a key is a contiguous run and a prefix query is two binary
    # searches. This is the section the decoder walks.
    index = Blob()
    groups: dict[str, list[int]] = defaultdict(list)
    for i, (syls, _, _) in enumerate(kept):
        groups[syls].append(i)
    ordered_keys = sorted(groups, key=lambda k: [syl_id[s] for s in k.split()])
    index.u32(len(ordered_keys))
    for key in ordered_keys:
        ids = [syl_id[s] for s in key.split()]
        index.varint(len(ids))
        for i in ids:
            index.varint(i)
        members = groups[key]
        index.varint(len(members))
        for m in members:
            index.varint(entry_offsets[m])

    # Characters, grouped by syllable id, each list weight-sorted: the fallback when no word
    # matches, and the source of per-character candidates.
    # Sorted by the stronger weight, for the same reason the words are: a character the Taiwan
    # model knows and the mainland one does not must survive the per-syllable cut.
    chars_by_syl: dict[int, list] = defaultdict(list)
    for (ch, syl), weight in chars.items():
        chars_by_syl[syl_id[syl]].append((max(weight), ch, weight))
    chars_sec = Blob()
    chars_sec.u32(len(chars_by_syl))
    for sid in sorted(chars_by_syl):
        items = sorted(chars_by_syl[sid], key=lambda t: (-t[0], t[1]))[:MAX_PER_KEY]
        chars_sec.varint(sid)
        chars_sec.varint(len(items))
        for _rank, ch, weight in items:
            chars_sec.utf8(ch)
            chars_sec.varint(weight[REGION_CN])
            chars_sec.varint(weight[REGION_TW])

    bigram_sec = Blob()
    bigram_sec.u32(len(pairs))
    for (a, b), count in pairs:
        bigram_sec.utf8(a)
        bigram_sec.utf8(b)
        bigram_sec.varint(count)

    def table_section(table: dict) -> Blob:
        blob = Blob()
        blob.u32(len(table))
        for k in sorted(table):
            blob.utf8(k)
            blob.utf8(table[k])
        return blob

    sections = [
        (SEC_SYLLABLES, syl_sec),
        (SEC_ENTRIES, entries),
        (SEC_INDEX, index),
        (SEC_CHARS, chars_sec),
        (SEC_BIGRAM, bigram_sec),
        (SEC_ST_CHARS, table_section(st_chars)),
        (SEC_ST_PHRASES, table_section(st_phrases)),
    ]

    header = Blob()
    header.buf += MAGIC
    header.u32(VERSION)
    header.u32(len(sections))
    offset = len(header) + len(sections) * 12
    placed = []
    for sec_id, blob in sections:
        pad = (-offset) % 8
        offset += pad
        placed.append((sec_id, offset, len(blob), pad, blob))
        offset += len(blob)

    with open(out_path, "wb") as f:
        f.write(header.buf)
        for sec_id, off, length, _pad, _blob in placed:
            f.write(struct.pack("<III", sec_id, off, length))
        for _sec_id, _off, _length, pad, blob in placed:
            f.write(b"\0" * pad)
            f.write(blob.buf)

    size = os.path.getsize(out_path)
    log(f"wrote {out_path}: {size / 1e6:.1f} MB")
    for sec_id, _off, length, _pad, _blob in placed:
        log(f"  section {sec_id}: {length / 1e6:.2f} MB")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--work", default="zhwork", help="scratch directory for downloads")
    ap.add_argument("--out", default="app/src/main/assets/pinyin.bin")
    ap.add_argument("--max-entries", type=int, default=450_000,
                    help="global cap on word entries (0 for no cap)")
    args = ap.parse_args()
    build(args.work, args.out, args.max_entries)


if __name__ == "__main__":
    main()
