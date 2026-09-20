#!/usr/bin/env python3
"""
Reads a built pinyin.bin and prints what is actually in it.

The builder's log says how many entries it wrote; this says whether the *right* ones are there
and what weights they carry, which is the only way to check a ranking claim without a device.

    tools/dump_pinyin_dict.py --dict app/src/main/assets/pinyin.bin --key "niu rou mian"
    tools/dump_pinyin_dict.py --word 蚵仔煎
"""

from __future__ import annotations

import argparse
import struct

REGION_NAMES = ("cn", "tw")


class Reader:
    def __init__(self, path: str) -> None:
        self.data = open(path, "rb").read()
        magic = self.data[:4]
        if magic not in (b"PYD2", b"PYD3"):
            raise SystemExit(f"not a pinyin dictionary: {magic!r}")
        self.version = 3 if magic == b"PYD3" else 2
        count = struct.unpack_from("<I", self.data, 8)[0]
        self.sections = {}
        for i in range(count):
            sid, off, length = struct.unpack_from("<III", self.data, 12 + i * 12)
            self.sections[sid] = (off, length)
        self.syllables = self._syllables()

    # --- primitives ---
    def _varint(self, pos: list) -> int:
        result = shift = 0
        while True:
            b = self.data[pos[0]]
            pos[0] += 1
            result |= (b & 0x7F) << shift
            if not b & 0x80:
                return result
            shift += 7

    def _string(self, pos: list) -> str:
        n = self._varint(pos)
        s = self.data[pos[0]:pos[0] + n].decode("utf-8")
        pos[0] += n
        return s

    def _syllables(self) -> list:
        off, _ = self.sections[1]
        n = struct.unpack_from("<I", self.data, off)[0]
        pos = [off + 4]
        return [self._string(pos) for _ in range(n)]

    def entry(self, offset: int):
        """(word, cn_weight, tw_weight) at an offset into the entries section."""
        base, _ = self.sections[2]
        pos = [base + offset]
        word = self._string(pos)
        cn = self._varint(pos)
        tw = self._varint(pos) if self.version >= 3 else 0
        return word, cn, tw

    def keys(self):
        """Yields (key string, [entries]) for every syllable key, in stored order."""
        off, _ = self.sections[3]
        n = struct.unpack_from("<I", self.data, off)[0]
        pos = [off + 4]
        for _ in range(n):
            count = self._varint(pos)
            ids = [self._varint(pos) for _ in range(count)]
            members = self._varint(pos)
            offsets = [self._varint(pos) for _ in range(members)]
            yield " ".join(self.syllables[i] for i in ids), offsets

    def chars(self):
        """Yields (syllable, [(char, cn, tw)])."""
        off, _ = self.sections[4]
        n = struct.unpack_from("<I", self.data, off)[0]
        pos = [off + 4]
        for _ in range(n):
            sid = self._varint(pos)
            count = self._varint(pos)
            items = []
            for _ in range(count):
                ch = self._string(pos)
                cn = self._varint(pos)
                tw = self._varint(pos) if self.version >= 3 else 0
                items.append((ch, cn, tw))
            yield self.syllables[sid], items


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dict", default="app/src/main/assets/pinyin.bin")
    ap.add_argument("--key", action="append", default=[], help="syllable key, e.g. 'niu rou mian'")
    ap.add_argument("--word", action="append", default=[], help="find a word anywhere")
    ap.add_argument("--chars", action="append", default=[], help="characters for a syllable")
    ap.add_argument("--limit", type=int, default=12)
    args = ap.parse_args()

    r = Reader(args.dict)
    print(f"format v{r.version}, {len(r.syllables)} syllables")

    if args.key:
        wanted = set(args.key)
        for key, offsets in r.keys():
            if key in wanted:
                print(f"\n=== {key} ===")
                rows = [r.entry(o) for o in offsets[:args.limit]]
                for word, cn, tw in rows:
                    print(f"  {word:<10} cn={cn:<12} tw={tw}")

    if args.word:
        wanted = set(args.word)
        print()
        for key, offsets in r.keys():
            for o in offsets:
                word, cn, tw = r.entry(o)
                if word in wanted:
                    print(f"  {word:<10} key={key:<24} cn={cn:<12} tw={tw}")

    if args.chars:
        wanted = set(args.chars)
        for syl, items in r.chars():
            if syl in wanted:
                print(f"\n=== chars for {syl} ===")
                for ch, cn, tw in items[:args.limit]:
                    print(f"  {ch}  cn={cn:<12} tw={tw}")


if __name__ == "__main__":
    main()
