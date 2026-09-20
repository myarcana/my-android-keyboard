#!/usr/bin/env python3
"""
Bopomofo (注音) to Hanyu Pinyin, in the spellings this keyboard's dictionary uses.

The Taiwan language model is keyed by bopomofo because that is how Taiwan types; this keyboard
is a pinyin keyboard. Converting the readings at *build* time rather than on device is what lets
the Taiwan vocabulary sit in the same syllable-indexed structure as everything else, so one
decoder serves both and nothing pays a conversion cost per keystroke.

The mapping is finite and total: 407 toneless syllables appear in McBopomofo's data, and
`verify()` asserts every one of them lands on a syllable the shipped table already knows. A
syllable that failed to convert would silently drop a word from the dictionary, so this refuses
to produce output it cannot account for rather than dropping the entry.

Spellings follow the dictionary, not a textbook -- `lv`/`lve` rather than `lü`/`lüe`, because a
phone keyboard has no `ü` key and the rest of the asset is already spelled that way.
"""

from __future__ import annotations

# Tone marks; the keyboard is toneless, so these are stripped rather than encoded.
TONES = "ˊˇˋ˙"

# --- the three series -------------------------------------------------------------------------

INITIALS = {
    "ㄅ": "b", "ㄆ": "p", "ㄇ": "m", "ㄈ": "f",
    "ㄉ": "d", "ㄊ": "t", "ㄋ": "n", "ㄌ": "l",
    "ㄍ": "g", "ㄎ": "k", "ㄏ": "h",
    "ㄐ": "j", "ㄑ": "q", "ㄒ": "x",
    "ㄓ": "zh", "ㄔ": "ch", "ㄕ": "sh", "ㄖ": "r",
    "ㄗ": "z", "ㄘ": "c", "ㄙ": "s",
}

MEDIALS = {"ㄧ": "i", "ㄨ": "u", "ㄩ": "v"}

FINALS = {
    "ㄚ": "a", "ㄛ": "o", "ㄜ": "e", "ㄝ": "e",
    "ㄞ": "ai", "ㄟ": "ei", "ㄠ": "ao", "ㄡ": "ou",
    "ㄢ": "an", "ㄣ": "en", "ㄤ": "ang", "ㄥ": "eng",
    "ㄦ": "er",
}

# Syllables whose pinyin spelling is not the concatenation of its parts. Pinyin orthography
# rewrites a bare medial into its `y-`/`w-` form and contracts several rimes, and none of that
# falls out of gluing the pieces together.
WHOLE = {
    # Standalone medials.
    "ㄧ": "yi", "ㄨ": "wu", "ㄩ": "yu",
    # The retroflex/sibilant series written with a bare vowel.
    "ㄓ": "zhi", "ㄔ": "chi", "ㄕ": "shi", "ㄖ": "ri",
    "ㄗ": "zi", "ㄘ": "ci", "ㄙ": "si",
    "ㄦ": "er",
}

# Rime contractions, keyed by (medial, final) once an initial is present.
CONTRACTIONS = {
    ("i", "ou"): "iu",     # ㄧㄡ  niu, liu
    ("i", "en"): "in",     # ㄧㄣ  bin, min, jin  -- not "bien"
    ("i", "eng"): "ing",   # ㄧㄥ  bing, ming, jing -- not "bieng"
    ("u", "ei"): "ui",     # ㄨㄟ  hui, gui
    ("u", "en"): "un",     # ㄨㄣ  lun, cun
    ("v", "en"): "un",     # ㄩㄣ  jun, qun
    ("v", "an"): "uan",    # ㄩㄢ  juan  (j/q/x only; ㄌㄩㄢ does not occur)
    ("v", "e"): "ue",      # ㄩㄝ  jue, xue
}

# Medial spellings when the syllable has no initial: pinyin writes them as y-/w-.
NO_INITIAL = {
    ("i", ""): "yi", ("i", "a"): "ya", ("i", "e"): "ye", ("i", "ai"): "yai",
    ("i", "ao"): "yao", ("i", "ou"): "you", ("i", "an"): "yan", ("i", "en"): "yin",
    ("i", "ang"): "yang", ("i", "eng"): "ying", ("i", "o"): "yo",
    ("u", ""): "wu", ("u", "a"): "wa", ("u", "o"): "wo", ("u", "ai"): "wai",
    ("u", "ei"): "wei", ("u", "an"): "wan", ("u", "en"): "wen",
    ("u", "ang"): "wang", ("u", "eng"): "weng",
    ("v", ""): "yu", ("v", "e"): "yue", ("v", "an"): "yuan", ("v", "en"): "yun",
    ("v", "eng"): "yong",
}

# `ㄨㄥ` after an initial is spelled `-ong`, not `-ueng`: dong, tong, zhong.
# `ㄩㄥ` after an initial is `-iong`: jiong, xiong.
MEDIAL_NG = {("u", "eng"): "ong", ("v", "eng"): "iong"}


def syllable(bpmf: str) -> str | None:
    """
    One bopomofo syllable to pinyin, or None if it is not a syllable this can express.

    Returning None rather than a best guess is deliberate: the caller counts the failures and
    the build refuses to ship if any appear, which is what keeps a silent spelling error from
    removing words from the dictionary.
    """
    s = "".join(c for c in bpmf if c not in TONES)
    if not s:
        return None
    if s in WHOLE:
        return WHOLE[s]

    initial = ""
    i = 0
    if s[0] in INITIALS:
        initial = INITIALS[s[0]]
        i = 1

    medial = ""
    if i < len(s) and s[i] in MEDIALS:
        medial = MEDIALS[s[i]]
        i += 1

    final = ""
    if i < len(s) and s[i] in FINALS:
        final = FINALS[s[i]]
        i += 1

    if i != len(s):
        return None  # leftover symbols: not a syllable we understand

    if not initial:
        if not medial:
            return final or None
        return NO_INITIAL.get((medial, final))

    if not medial:
        if not final:
            return None
        rime = final
    elif (medial, final) in MEDIAL_NG:
        rime = MEDIAL_NG[(medial, final)]
    elif (medial, final) in CONTRACTIONS:
        rime = CONTRACTIONS[(medial, final)]
    else:
        rime = medial + final

    # j/q/x are never written with `v`: ju, qu, xu already imply the umlaut, so the contracted
    # `ue`/`uan`/`un` rimes above are already correct for them.
    if initial in ("j", "q", "x") and rime and rime[0] == "v":
        rime = "u" + rime[1:]
    # `n`/`l` are the pair that genuinely contrast (nu vs nv), and the dictionary spells the
    # umlaut `v`: ㄋㄩㄝ is `nve`, not the `nue` the contraction table produces for j/q/x.
    if initial in ("n", "l") and rime == "ue":
        rime = "ve"
    return initial + rime


def reading(bpmf_syllables) -> list[str] | None:
    """A whole word's reading, or None if any syllable fails."""
    out = []
    for b in bpmf_syllables:
        p = syllable(b)
        if p is None:
            return None
        out.append(p)
    return out
