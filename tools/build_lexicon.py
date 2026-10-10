#!/usr/bin/env python3
"""Build the offline lexicon the glide decoder matches against.

A glide is a shape, and a shape on its own is ambiguous: "hello" and "gelko" trace nearly the
same line. What separates them is that one is a word people write and the other is not, so the
decoder needs frequencies, not just a word list -- and it needs them for the words this person
will actually glide, which includes "ok", "yeah" and "I'm" and does not include the long tail of
web2's archaic nouns.

Three sources, joined:

  Norvig's count_1w.txt      333k words with counts from the Google Web Trillion Word Corpus.
                             The ranking. Punctuation is stripped in it, so contractions are
                             missing entirely and junk from crawled HTML is present.
  /usr/share/dict/words      macOS's web2 (/usr/share/dict/web2 on Debian). Not a ranking and
                             not modern, but a good answer to "is this a word at all", which is
                             what the tail of the crawl needs.
  wordfreq 3.1.1 (pip)       Used only as yes/no, and only for the long-word pass: a second
                             corpus, so a misspelling one crawl published often is not enough.

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
# macOS links words to web2; Debian's `dictionaries-common` moves that link aside and leaves web2
# itself, which is the same file. Either gives the same lexicon, byte for byte.
SYSTEM_DICTS = [pathlib.Path("/usr/share/dict/words"), pathlib.Path("/usr/share/dict/web2")]

OUT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/lexicon_en.tsv"

# How many words survive. Glide decoding gets *worse* with a bigger lexicon past some point:
# every rare word is another shape competing with a common one, and the words a person actually
# glides are overwhelmingly in the first few thousand. 40k keeps the tail that matters (names,
# plurals, "-ing" forms) without stocking the decoder with Scrabble words. This is the head of
# the lexicon, not all of it: long words from further down are added by the pass described at
# LONG_WORD_LIMIT, which is what brings the total to about 70k.
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
    # The same gap: GitHub launched in 2008, after the crawl, so it has no count at all and could
    # not be glided. Scored off wordfreq 3.1 (10^-5.82) times the crawl's median count per unit of
    # wordfreq frequency (6.5e11, over wordfreq ranks 2k-12k), which lands at ~1M -- beside
    # "gotham" and well under "guitar", the words sharing its endpoints.
    "github": 1_000_000,
    # The corpus was tokenized Penn Treebank style, which splits "cannot" into "can not" and
    # "gimme"/"lemme" into "gim me"/"lem me" -- the same rule that left "gon", "wan" and "ta"
    # in the crawl and "gonna", "wanna" and "gotta" on this list. What survives as one token is
    # only the residue the tokenizer missed: 88k for "cannot", rank 106,594, far past the cap,
    # so one of the commonest words in written English could not be glided at all.
    # Scored off "can't", which the crawl does count whole (as "cant", 8.4M), times each word's
    # frequency relative to "can't" in wordfreq 3.1 (a corpus that does not split them):
    # cannot 10^-0.69, gimme 10^-2.23, lemme 10^-2.61.
    "cannot": 1_700_000, "gimme": 50_000, "lemme": 20_000,
}

# Words neither source ranks, kept anyway. Not a place for vocabulary in general -- that is what
# LONG_WORD_LIMIT is for -- only for words the crawl never saw at all, so no limit could reach
# them. Scored at the crawl's own floor, the least a counted word can have.
UNCOUNTED = {"heteronym", "heteronyms"}
UNCOUNTED_COUNT = 12_000

# Nationality and people words, kept at their crawl count whatever the dictionary says.
#
# Past rank 15,000 a word must be in web2, and web2 is a 1934 dictionary: it has "Chinese" and
# "Syrian" but not "Taiwanese" (rank 22,658, 1.3M uses), because Taiwan was Formosa then. Nor
# "Bangladeshi", "Singaporean", "Kazakh" or "Zimbabwean" -- none of those states existed. Their
# plurals fail for another reason: "syrians" is rank 60,006, just past where the 40,000 cut stops,
# and too short for the long-word pass. Each entry here brings its "-s" plural along when the
# crawl counts one.
#
# A list rather than a suffix rule, because the rule does not survive contact with the crawl:
# place + "i"/"n"/"ian" also reads "martini" as Martin's and "staten" as a state's. Left off on
# purpose: "lao" (three letters, and a pinyin syllable) and "nigerien", whose glide is almost
# exactly "nigerian"'s.
DEMONYMS = {
    # Taiwan's own: its people and the two languages besides Mandarin most spoken there.
    "taiwanese", "hakka", "hokkien",
    # Missing outright: web2 predates the country or never listed the word.
    "angolan", "bahraini", "bangladeshi", "barbadian", "belarusian", "belizean", "bhutanese",
    "burundian", "cameroonian", "chadian", "cypriot", "emirati", "eritrean", "gabonese",
    "gambian", "ghanaian", "grenadian", "guinean", "guyanese", "ivorian", "kazakh",
    "kazakhstani", "kosovar", "kuwaiti", "kyrgyz", "laotian", "malawian", "malian", "maldivian",
    "mauritanian", "mauritian", "moldovan", "montenegrin", "mozambican", "namibian",
    "paraguayan", "qatari", "rwandan", "salvadoran", "senegalese", "singaporean", "slovakian",
    "somalian", "surinamese", "swazi", "tanzanian", "togolese", "tongan", "trinidadian",
    "uighur", "uyghur", "zambian", "zimbabwean",
    # Already in, listed for their plurals: "syrians" and the rest fall past the 40,000 cut.
    "algerian", "argentinian", "austrian", "azerbaijani", "belgian", "bolivian", "bosnian",
    "bulgarian", "cambodian", "chilean", "colombian", "croatian", "dominican", "ecuadorian",
    "estonian", "ethiopian", "fijian", "georgian", "guatemalan", "haitian", "honduran",
    "hungarian", "indonesian", "jamaican", "jordanian", "kenyan", "latvian", "liberian",
    "libyan", "lithuanian", "malaysian", "mongolian", "moroccan", "nepali", "nicaraguan",
    "nigerian", "norwegian", "panamanian", "peruvian", "romanian", "saudi", "serbian", "slovak",
    "slovenian", "somali", "syrian", "tunisian", "ugandan", "uruguayan", "uzbek", "venezuelan",
    "yemeni",
}

# How far down the crawl a *long* word may be found, beyond the words DEFAULT_LIMIT keeps.
#
# The limit above is a count, and it stops at rank ~59k -- which leaves out ordinary words the
# crawl simply saw less of: "homophone" is rank 277k, "homophones" 151k, "palindrome" 92k. A
# long word is not the risk a short one is: its shape is long and specific, so it cannot hide
# inside another gesture the way "wud" hides in "would", and the decoder will only reach for it
# when the path actually spells it. So long words get a second, much deeper pass -- but under a
# strict test, because the deep crawl is where the junk is: the word must be a lowercase web2
# headword or an inflection of one. No compounds (the tail is full of glued-together tokens
# like "shoppingcart"), no capitalised entries (web2's proper nouns).
LONG_WORD_MIN_LETTERS = 7
LONG_WORD_LIMIT = 300_000

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


def system_dict() -> pathlib.Path | None:
    for path in SYSTEM_DICTS:
        if path.exists():
            return path
    return None


def read_dictionary() -> set[str]:
    path = system_dict()
    if path is None:
        print(f"warning: {SYSTEM_DICTS[0]} is missing; the crawl's tail will not be filtered",
              file=sys.stderr)
        return set()
    return {w.strip().lower() for w in path.read_text(errors="replace").splitlines()}


def read_common_nouns() -> set[str]:
    """web2's entries that are not capitalised: its common words, without its proper nouns."""
    path = system_dict()
    if path is None:
        return set()
    return {w.strip() for w in path.read_text(errors="replace").splitlines()
            if w.strip() and w.strip()[0].islower()}


def read_second_corpus() -> dict[str, float]:
    """Every English word wordfreq has a frequency for, with that frequency.

    The deep crawl's second filter. The suffix rules read "switchs" and "tecnologies" as
    inflections of real words, and each is a misspelling someone published often enough to be
    counted; a glide dictionary that holds a misspelling will type it. A second corpus built
    from different sources (subtitles, Wikipedia, books, Reddit, news) does not share one
    crawl's typos, and requiring both drops about 7% of the long tail, most of it junk. The
    ranking stays count_1w's, so the scale is one corpus's.
    """
    try:
        import wordfreq
    except ImportError:
        sys.exit("the long-word pass needs wordfreq (pip install wordfreq==3.1.1); without it "
                 "the lexicon would silently come out different")
    return wordfreq.get_frequency_dict("en", "large")


# How much commoner the one-letter-shorter spelling must be before a doubled letter is read as a
# typo. Doubling is the misspelling both corpora share -- "happenned", "writting", "openning" are
# in wordfreq too -- so presence cannot catch it, but the ratio can: "writing" outnumbers
# "writting" a thousandfold. 30 keeps legitimate pairs that merely differ in frequency
# ("barrack"/"barack", "dragoons"/"dragons" sit below 10) and the British "-lled" spellings.
DOUBLED_LETTER_RATIO = 30


def doubled_typo(word: str, frequency: dict[str, float]) -> bool:
    """Whether dropping one of a doubled letter gives a far commoner word."""
    own = frequency.get(word, 0.0)
    for i in range(1, len(word)):
        if word[i] == word[i - 1]:
            single = word[:i] + word[i + 1:]
            if frequency.get(single, 0.0) > DOUBLED_LETTER_RATIO * own:
                return True
    return False


def known_strictly(word: str, headwords: set[str]) -> bool:
    """A headword, or a headword plus a suffix: `known` without the compound rule.

    The suffix rules are also spelled out more exactly than `known` needs them to be, because
    here they are the only defence the deep crawl meets. A dropped "e" comes back only before a
    vowel ("hoping" is "hope"; "revenus" is not "revenue"), and "es" only follows the endings
    English puts it after ("boxes", not "adultes").
    """
    if word in headwords:
        return True
    for suffix in SUFFIXES:
        if not word.endswith(suffix) or len(word) - len(suffix) < 3:
            continue
        stem = word[: -len(suffix)]
        if suffix == "es" and not stem.endswith(("s", "x", "z", "ch", "sh", "o")):
            continue
        if stem in headwords:
            return True
        if suffix[0] in "aeiou" and stem + "e" in headwords:
            return True
        if suffix == "ies" and stem + "y" in headwords:
            return True
        if len(stem) > 3 and stem[-1] == stem[-2] and stem[:-1] in headwords:
            return True
    return False


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
    core = len(kept)

    headwords = read_common_nouns()
    attested = read_second_corpus()
    for rank, (word, count) in enumerate(ordered[:LONG_WORD_LIMIT]):
        if word in kept or len(word) < LONG_WORD_MIN_LETTERS or word not in attested:
            continue
        if doubled_typo(word, attested):
            continue
        if is_plausible(word, rank, dictionary, ranks) and known_strictly(word, headwords):
            kept[word] = count
    long_words = len(kept) - core

    for word, count in INFORMAL.items():
        kept[word] = max(kept.get(word, 0), count)
    for word in UNCOUNTED:
        kept[word] = max(kept.get(word, 0), UNCOUNTED_COUNT)
    demonyms = 0
    for singular in DEMONYMS:
        for word in (singular, singular + "s"):
            if word not in kept and counts.get(word, 0) > 0:
                kept[word] = counts[word]
                demonyms += 1

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
    print(f"  {core} from the crawl's head, {long_words} long words from its tail")
    print(f"  {demonyms} nationality words the dictionary check had dropped")
    print(f"  {added} contractions scored from their bare form, {len(DROP_BARE)} bare forms dropped")
    if dropped_by_guard:
        print(f"  {len(dropped_by_guard)} words dropped by the repository's commit guard")
    print("  head: " + " ".join(w for w, _ in rows[:12]))
    print("  tail: " + " ".join(w for w, _ in rows[-12:]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
