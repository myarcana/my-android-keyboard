#!/usr/bin/env python3
"""Build the offline lexicon the glide decoder matches against.

A glide is a shape, and a shape on its own is ambiguous: "hello" and "gelko" trace nearly the
same line. What separates them is that one is a word people write and the other is not, so the
decoder needs frequencies, not just a word list -- and it needs them for the words this person
will actually glide, which includes "ok", "yeah" and "I'm" and does not include the long tail of
web2's archaic nouns.

Two sources, joined:

  Norvig's count_1w.txt      333k words with counts from the Google Web Trillion Word Corpus.
                             The ranking. Punctuation is stripped in it, so contractions are
                             missing entirely and junk from crawled HTML is present.
  /usr/share/dict/words      macOS's web2. Not a ranking and not modern, but a good answer to
                             "is this a word at all", which is what the tail of the crawl needs.

Contractions are added by hand, scored from their apostrophe-less form in the crawl -- "don't"
gets the count of "dont", because that is the same word typed by someone whose keyboard made
the apostrophe inconvenient. Some of those bare forms are then dropped: nobody glides "dont"
meaning anything other than "don't", and leaving both in makes them compete for one shape.

The output is committed. The app must never fetch anything, and the build has to work with no
network at all.

Usage: tools/build_lexicon.py [--offline count_1w.txt] [--limit N]
"""

import argparse
import math
import pathlib
import subprocess
import sys
import urllib.request

COUNTS = "https://norvig.com/ngrams/count_1w.txt"
SYSTEM_DICT = pathlib.Path("/usr/share/dict/words")

OUT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/lexicon_en.tsv"

# How many words survive. Glide decoding gets *worse* with a bigger lexicon past some point:
# every rare word is another shape competing with a common one, and the words a person actually
# glides are overwhelmingly in the first few thousand. 40k keeps the tail that matters (names,
# plurals, "-ing" forms) without stocking the decoder with Scrabble words.
DEFAULT_LIMIT = 40_000

# One in this many uses of "they" is assumed to be "they've", "they'll" or the like. See the
# contraction loop below for why a floor is needed at all.
CONTRACTION_SHARE = 250

# Crawled HTML leaves these behind at frequencies no real word reaches. They are not typos, so
# no dictionary check catches them.
WEB_JUNK = {
    "http", "https", "www", "com", "net", "org", "html", "htm", "php", "asp", "aspx", "cgi",
    "url", "href", "img", "jpg", "jpeg", "gif", "png", "pdf", "rss", "xml", "utf", "iso",
    "nbsp", "amp", "quot", "gt", "lt", "br", "td", "tr", "th", "li", "ul", "div", "src",
    "aaa", "aa", "ab", "ac", "ad", "ae", "af", "ag", "ah", "ak", "al", "ap", "ar", "cc",
    "cd", "ck", "cm", "dd", "ee", "ff", "ii", "ll", "mm", "nn", "oo", "pp", "ss", "tt", "uu",
    "vv", "ww", "xx", "yy", "zz", "xxx", "sex", "porn", "sexy", "nude", "casino", "viagra",
}

# Single letters are keys, not words: gliding one is impossible (a glide is two keys minimum)
# and having them in the lexicon only lets a sloppy tap decode as a word.
KEEP_SINGLE = {"a", "i"}

# How far down the crawl a word of each length may be found. Longer words are unrestricted.
SHORT_WORD_RANK_LIMIT = {2: 2_500, 3: 10_000, 4: 20_000}

# Words the crawl ranks too low for how often they are typed on a phone, or misses entirely.
# The count given is a raw corpus count, on the same scale as count_1w's.
INFORMAL = {
    "ok": 40_000_000, "okay": 30_000_000, "hi": 30_000_000, "hey": 20_000_000,
    "yeah": 25_000_000, "yep": 4_000_000, "nope": 3_000_000, "um": 3_000_000,
    "uh": 4_000_000, "oh": 30_000_000, "wow": 6_000_000, "lol": 8_000_000,
    "thanks": 60_000_000, "thx": 2_000_000, "please": 90_000_000, "sorry": 50_000_000,
    "gonna": 6_000_000, "wanna": 4_000_000, "gotta": 3_000_000, "kinda": 2_000_000,
    "app": 8_000_000, "apps": 5_000_000, "email": 90_000_000, "online": 200_000_000,
    "wifi": 3_000_000, "phone": 120_000_000, "text": 150_000_000, "meeting": 40_000_000,
    "tonight": 30_000_000, "tomorrow": 40_000_000, "today": 150_000_000,
    # count_1w is a 2012 crawl of older text and predates the word entering general English, so
    # "emoji" is missing outright rather than merely ranked low. Scored beside "wifi", the other
    # entry here that the crawl never saw. "emojis" is the plural people actually write; the
    # Japanese-faithful "emoji" plural is not what a phone keyboard should be insisting on.
    "emoji": 3_000_000, "emojis": 2_000_000,
}

# Contractions, scored from their bare form in the crawl. The multiplier is not tuning: the bare
# form is what the corpus counted, and it undercounts the apostrophe spelling by an unknown
# amount, so 1.0 is the honest choice and the ordering between contractions is what matters.
CONTRACTIONS = [
    "I'm", "I've", "I'll", "I'd", "it's", "that's", "don't", "doesn't", "didn't", "can't",
    "won't", "wouldn't", "couldn't", "shouldn't", "isn't", "aren't", "wasn't", "weren't",
    "haven't", "hasn't", "hadn't", "you're", "you've", "you'll", "you'd", "we're", "we've",
    "we'll", "we'd", "they're", "they've", "they'll", "they'd", "he's", "he'll", "he'd",
    "she's", "she'll", "she'd", "there's", "here's", "what's", "who's", "let's", "that'll",
    "ain't", "y'all", "o'clock", "we're", "how's", "where's", "when's", "why's",
]

# Contractions whose bare form is a real word, but a *less* written one: "it's" outnumbers "its"
# and "I'll" outnumbers "ill". Both spell the same glide -- identical letters, identical shape --
# so one of the pair can never be produced, and this says which one that is. Pairs left off the
# list keep the bare word: "well" beats "we'll", "were" beats "we're", "shell" beats "she'll".
PREFER_CONTRACTION = {"it's", "I'll", "I'd", "we'd", "he'd", "let's"}

# Bare forms that exist in the crawl only as a mistyped contraction. Dropping them stops two
# entries competing for one shape -- and the contraction is the one that was meant.
DROP_BARE = {
    "im", "ive", "dont", "doesnt", "didnt", "cant", "wont", "wouldnt", "couldnt", "shouldnt",
    "isnt", "arent", "wasnt", "werent", "havent", "hasnt", "hadnt", "youre", "youve", "youll",
    "youd", "weve", "theyre", "theyve", "theyll", "theyd", "hes", "shes", "theres",
    "heres", "whats", "whos", "thats", "thatll", "aint", "yall", "oclock", "hows", "wheres",
    "whens",
}


def fetch(offline: pathlib.Path | None) -> str:
    if offline:
        return offline.read_text(encoding="utf-8", errors="replace")
    with urllib.request.urlopen(COUNTS) as response:
        return response.read().decode("utf-8", errors="replace")


def read_counts(text: str) -> dict[str, int]:
    counts: dict[str, int] = {}
    for line in text.splitlines():
        word, _, count = line.partition("\t")
        if not count:
            continue
        word = word.strip().lower()
        if word and word not in counts:
            counts[word] = int(count)
    return counts


def read_guarded() -> list[str]:
    """Strings this repository refuses to commit.

    The repo keeps a pseudonymous identity, enforced by a commit-msg hook that greps every blob
    for a short list of strings in `.git/info/bad_strings` and aborts if it finds one. A word list
    built from a web crawl contains ordinary English words that collide with that list -- names,
    mostly, which is the whole point of it -- and the hook cannot tell a leak from a dictionary.

    Dropping them here rather than arguing with the hook costs a handful of proper nouns out of
    forty thousand words, which the decoder will not miss, and leaves the guard doing its job.
    Reading the list rather than hard-coding it keeps the strings out of this file too.
    """
    try:
        git_dir = subprocess.run(
            ["git", "rev-parse", "--git-dir"],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return []
    path = pathlib.Path(git_dir) / "info" / "bad_strings"
    if not path.exists():
        return []
    return [line.strip().lower() for line in path.read_text().splitlines() if line.strip()]


def read_dictionary() -> set[str]:
    if not SYSTEM_DICT.exists():
        print(f"warning: {SYSTEM_DICT} is missing; the crawl's tail will not be filtered",
              file=sys.stderr)
        return set()
    return {w.strip().lower() for w in SYSTEM_DICT.read_text(errors="replace").splitlines()}


# Suffixes web2 does not list separately. It is a dictionary of headwords, so it has "peep" and
# "message" but not "peeped" or "messaged" -- and an inflected form is exactly what a phone types.
SUFFIXES = ("s", "es", "ed", "d", "ing", "er", "ers", "est", "ly", "'s", "ies")

# The shortest part a compound may be split into. Two letters would make "as", "at" and "in"
# available as halves and let any short token decompose into something; four rejects real
# compounds like "doorknob". Three is the point where both halves have to be words in their
# own right, which is what makes the check mean anything.
MIN_COMPOUND_PART = 3

# How far down the crawl a *part* of a compound may be found. web2 lists three-letter
# curiosities -- "ume", "ait", "phe" -- that are dictionary words nobody writes, and each one is
# a licence to split some crawl artifact into two "words": "docume", a truncated HTML token,
# is "doc" + "ume". Requiring both halves to be words people actually write, not merely words
# web2 records, is the same distrust of short entries that SHORT_WORD_RANK_LIMIT applies above.
COMPOUND_PART_RANK_LIMIT = 30_000


def known(word: str, dictionary: set[str], ranks: dict[str, int]) -> bool:
    """Whether the word, or a form web2 would list it under, is in the dictionary."""
    if word in dictionary:
        return True
    for suffix in SUFFIXES:
        if not word.endswith(suffix) or len(word) - len(suffix) < 3:
            continue
        stem = word[: -len(suffix)]
        if stem in dictionary or stem + "e" in dictionary:
            return True
        # groceries -> grocery, strawberries -> strawberry. web2 lists the singular headword
        # and nothing else, so without this every "-ies" plural in English fails the check --
        # including ones far commoner than the proper nouns the tail filter lets through.
        if suffix == "ies" and stem + "y" in dictionary:
            return True
        # running -> run, bigger -> big: the doubled consonant is not part of the word.
        if len(stem) > 3 and stem[-1] == stem[-2] and stem[:-1] in dictionary:
            return True
    return is_compound(word, dictionary, ranks)


def is_compound(word: str, dictionary: set[str], ranks: dict[str, int]) -> bool:
    """Whether the word splits into two words web2 does list.

    web2 is a dictionary of headwords and English builds compounds freely, so "breadcrumbs",
    "lawnmower" and "babysitter" are absent from it while "bread", "crumb", "lawn", "mower",
    "baby" and "sitter" are all there. A headword check alone therefore rejects a whole class of
    ordinary words -- and rejects them *below* the crawl's proper nouns, which web2 does list.

    The head must be a headword outright. The tail is allowed the suffix rules, because the
    inflection on a compound lands on its end -- "breadcrumbs" is "bread" + "crumbs".

    Both halves must also be words the crawl sees often, not merely words web2 records. web2 is
    a complete dictionary, so it lists three-letter curiosities that license nonsense splits:
    without the rank floor "docume" is "doc" + "ume" and passes.
    """
    for cut in range(MIN_COMPOUND_PART, len(word) - MIN_COMPOUND_PART + 1):
        head, tail = word[:cut], word[cut:]
        if head not in dictionary or not common(head, ranks):
            continue
        if tail in dictionary and common(tail, ranks):
            return True
        if len(tail) > MIN_COMPOUND_PART and known(tail, dictionary, ranks) and common(tail, ranks):
            return True
    return False


def common(part: str, ranks: dict[str, int]) -> bool:
    """Whether a compound's half is a word people write, rather than one web2 merely lists."""
    return ranks.get(part, len(ranks)) <= COMPOUND_PART_RANK_LIMIT


def is_plausible(word: str, rank: int, dictionary: set[str], ranks: dict[str, int]) -> bool:
    """Whether a crawled token is a word someone would glide.

    The dictionary check is applied only to the tail. The head of the crawl is where the modern
    vocabulary lives -- "blog", "iphone", "website" are all absent from web2 -- and the tail is
    where the typos and product codes live, so one rule for both would either admit the junk or
    reject the vocabulary.
    """
    if not word.isascii() or not word.isalpha():
        return False
    if len(word) > 18:
        return False
    if len(word) == 1 and word not in KEEP_SINGLE:
        return False
    if word in WEB_JUNK:
        return False
    if rank > 15_000 and dictionary and not known(word, dictionary, ranks):
        return False
    # A short word has to earn its place much harder than a long one, because its shape is a
    # *subset* of longer gestures rather than a rival to them. A glide of "would" passes through
    # everything "wud" asks for and then keeps going, so an obscure three-letter entry does not
    # merely compete with the intended word, it beats it on every measure except frequency.
    # Both web2 and the crawl are full of them -- "wud", "hie", "ait", "phe", "tk", "nr" -- and
    # each one silently removes a common word from the decoder's reach.
    if rank > SHORT_WORD_RANK_LIMIT.get(len(word), 0) and len(word) in SHORT_WORD_RANK_LIMIT:
        return False
    return True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--offline", type=pathlib.Path, help="a local copy of count_1w.txt")
    parser.add_argument("--limit", type=int, default=DEFAULT_LIMIT)
    args = parser.parse_args()

    counts = read_counts(fetch(args.offline))
    if not counts:
        print("no counts read", file=sys.stderr)
        return 1
    dictionary = read_dictionary()

    ordered = sorted(counts.items(), key=lambda kv: -kv[1])
    # The crawl's rank for every token, so the compound check can ask whether a half is a word
    # people write as well as one web2 lists.
    ranks = {word: rank for rank, (word, _) in enumerate(ordered)}

    kept: dict[str, int] = {}
    for rank, (word, count) in enumerate(ordered):
        if len(kept) >= args.limit:
            break
        if is_plausible(word, rank, dictionary, ranks):
            kept[word] = count

    for word, count in INFORMAL.items():
        kept[word] = max(kept.get(word, 0), count)

    added = 0
    for word in CONTRACTIONS:
        bare = word.replace("'", "").lower()
        head = word.split("'")[0].lower()
        # The bare form's count is only evidence when the bare form is not itself a word.
        # "we'll" strips to "well", and taking that count would score the contraction as if
        # every use of "well" were one -- which is how "we'll" and "well" ended up tied on the
        # first run, competing for a shape only one of them can ever win.
        count = counts.get(bare, 0) if bare in DROP_BARE else 0
        # The bare form also undercounts badly for the rarer contractions: "theyve" is written by
        # almost nobody, while "they've" is written constantly, so scoring purely from the crawl
        # buries half of these below web2's archaic nouns. Flooring each at a fixed share of its
        # leading word -- one constant, applied to all of them -- keeps the family together and
        # still lets the crawl order them among themselves where it has real evidence.
        count = max(count, counts.get(head, 0) // CONTRACTION_SHARE)
        if word in PREFER_CONTRACTION and bare in kept:
            count = max(count, kept[bare] * 11 // 10)
        if count == 0:
            continue
        kept[word] = max(kept.get(word, 0), count)
        added += 1
    for bare in DROP_BARE:
        kept.pop(bare, None)

    guarded = read_guarded()
    dropped_by_guard = [w for w in kept if any(bad in w.lower() for bad in guarded)]
    for word in dropped_by_guard:
        del kept[word]

    # Score: the log of the count, in hundredths of a decade. Log because the decoder adds it to
    # a distance, and a linear frequency would let "the" outvote the shape of the gesture
    # entirely. Hundredths because a tenth of a decade is a 26% frequency step, which is coarse
    # enough to change an ordering that matters.
    rows = sorted(kept.items(), key=lambda kv: (-kv[1], kv[0]))
    lines = [f"{word}\t{round(100 * math.log10(count))}" for word, count in rows]

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"{OUT}: {len(rows)} words, {OUT.stat().st_size / 1024:.0f} KB")
    print(f"  {added} contractions scored from their bare form, {len(DROP_BARE)} bare forms dropped")
    if dropped_by_guard:
        print(f"  {len(dropped_by_guard)} words dropped by the repository's commit guard")
    print("  head: " + " ".join(w for w, _ in rows[:12]))
    print("  tail: " + " ".join(w for w, _ in rows[-12:]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
