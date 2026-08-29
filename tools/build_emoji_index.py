#!/usr/bin/env python3
"""Build the offline emoji index the suggestion bar searches.

Two Unicode sources, joined:

  emoji-test.txt          which sequences exist.
  CLDR annotations/en.xml the searchable words: a short name ("grinning face") plus the
                          keyword set the emoji is normally searched by ("smile", "grin").
  Unicode emoji frequency the order to prefer them in.

The output is written in the order the suggestion bar should offer them, so the app itself
holds no ranking data: it can take file order as the tiebreak and stay dumb.

Frequency matters more here than it looks. Canonical Unicode order is a taxonomy, not a
popularity ranking, and ranking by it makes the bar quietly wrong in a way that is hard to
argue with individually but obvious in use -- "car" finds the railway car, "water" the water
buffalo, "drink" the baby bottle. Unicode's own frequency study fixes all three, and the
emoji it does not cover are rare enough that canonical order is a fine fallback for them.

Run this only when refreshing to a new Unicode release. The generated asset is committed --
the app must never fetch anything, and the build must work with no network at all.

Usage: tools/build_emoji_index.py [--offline DIR]
"""

import argparse
import pathlib
import re
import sys
import urllib.request

EMOJI_TEST = "https://unicode.org/Public/emoji/latest/emoji-test.txt"
CLDR = "https://raw.githubusercontent.com/unicode-org/cldr/main/common/annotations/en.xml"
CLDR_DERIVED = (
    "https://raw.githubusercontent.com/unicode-org/cldr/main/common/annotationsDerived/en.xml"
)
FREQUENCY = "https://home.unicode.org/emoji/emoji-frequency/"

OUT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/emoji_en.tsv"

SKIN_TONES = {0x1F3FB, 0x1F3FC, 0x1F3FD, 0x1F3FE, 0x1F3FF}
VARIATION_SELECTOR = 0xFE0F

# Groups whose members are not things anyone searches for by name.
SKIP_GROUPS = {"Component"}


def fetch(url: str, offline: pathlib.Path | None, name: str) -> str:
    if offline:
        return (offline / name).read_text(encoding="utf-8")
    with urllib.request.urlopen(url) as response:
        return response.read().decode("utf-8")


# The frequency page is a table of <td>group</td><td>emoji emoji ...</td> rows, most frequent
# group first. Only the ordering is used, not the group numbers.
FREQUENCY_ROW = re.compile(r"<td[^>]*>\s*(\d+)\s*</td>\s*<td[^>]*>(.*?)</td>", re.S)


def parse_frequency(text: str) -> dict[str, int]:
    """Emoji to rank, most used first. Keys have U+FE0F stripped, as CLDR's do."""
    ranks: dict[str, int] = {}
    for _group, cell in FREQUENCY_ROW.findall(text):
        for token in re.sub(r"<[^>]+>", " ", cell).split():
            key = normalise(token.strip())
            if key and key not in ranks:
                ranks[key] = len(ranks)
    return ranks


def parse_emoji_test(text: str) -> list[tuple[str, str]]:
    """Fully-qualified emoji in canonical order, as (sequence, group)."""
    out = []
    group = ""
    for line in text.splitlines():
        if line.startswith("# group:"):
            group = line.split(":", 1)[1].strip()
            continue
        if not line or line.startswith("#"):
            continue
        codes, _, rest = line.partition(";")
        if not rest.strip().startswith("fully-qualified"):
            continue
        points = [int(c, 16) for c in codes.split()]
        # Skin-tone variants are typed from the base emoji's popup, not searched for.
        if any(p in SKIN_TONES for p in points):
            continue
        if group in SKIP_GROUPS:
            continue
        out.append(("".join(chr(p) for p in points), group))
    return out


ANNOTATION = re.compile(
    r'<annotation cp="([^"]+)"(?:\s+type="(tts)")?\s*>([^<]*)</annotation>'
)

XML_ENTITIES = {"&amp;": "&", "&lt;": "<", "&gt;": ">", "&quot;": '"', "&apos;": "'"}


def unescape(s: str) -> str:
    for entity, char in XML_ENTITIES.items():
        s = s.replace(entity, char)
    return s


def parse_cldr(text: str, names: dict, keywords: dict) -> None:
    for cp, kind, value in ANNOTATION.findall(text):
        cp = unescape(cp)
        value = unescape(value)
        if kind == "tts":
            names[cp] = value.strip()
        else:
            keywords[cp] = [w.strip() for w in value.split("|") if w.strip()]


def normalise(sequence: str) -> str:
    """CLDR strips U+FE0F from its cp attributes; emoji-test keeps it."""
    return "".join(c for c in sequence if ord(c) != VARIATION_SELECTOR)


def searchable(word: str) -> str:
    return word.lower().replace("’", "'")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--offline",
        type=pathlib.Path,
        help="directory holding emoji-test.txt, cldr_en.xml, cldr_en_derived.xml, uf.html",
    )
    args = parser.parse_args()

    emoji = parse_emoji_test(fetch(EMOJI_TEST, args.offline, "emoji-test.txt"))
    names: dict[str, str] = {}
    keywords: dict[str, list[str]] = {}
    parse_cldr(fetch(CLDR, args.offline, "cldr_en.xml"), names, keywords)
    # Derived annotations cover the sequences (flags, families) the base file leaves out;
    # they must not overwrite a hand-written annotation, so they are merged underneath.
    derived_names: dict[str, str] = {}
    derived_keywords: dict[str, list[str]] = {}
    parse_cldr(
        fetch(CLDR_DERIVED, args.offline, "cldr_en_derived.xml"),
        derived_names,
        derived_keywords,
    )
    names = {**derived_names, **names}
    keywords = {**derived_keywords, **keywords}

    frequency = parse_frequency(fetch(FREQUENCY, args.offline, "uf.html"))

    lines = []
    missing = 0
    ranked = 0
    for canonical, (sequence, _group) in enumerate(emoji):
        key = normalise(sequence)
        name = names.get(key) or names.get(sequence)
        words = keywords.get(key) or keywords.get(sequence) or []
        if not name and not words:
            missing += 1
            continue
        name = searchable(name or "")
        # The name's own words are searchable too: "grinning face" should match "grin".
        terms = dict.fromkeys(
            [searchable(w) for w in words] + [w for w in name.split() if len(w) > 1]
        )
        terms.pop(name, None)
        rank = frequency.get(key)
        if rank is None:
            rank = len(frequency) + canonical
        else:
            ranked += 1
        lines.append((rank, f"{sequence}\t{name}\t{'|'.join(terms)}"))

    lines.sort(key=lambda row: row[0])
    lines = [row[1] for row in lines]

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(
        f"{OUT}: {len(lines)} emoji, {ranked} of them frequency-ranked, "
        f"{missing} skipped for having no annotation"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
