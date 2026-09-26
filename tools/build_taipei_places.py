#!/usr/bin/env python3
"""
Builds `tools/places/taipei.tsv`: Taipei place names with Taiwan pinyin readings, for
`build_pinyin_dict.py` to merge into the Taiwan language model.

    tools/build_taipei_places.py --work ../zhwork --out tools/places/taipei.tsv

WHY THIS EXISTS
`zhongxiaoxinsheng` could not produce 忠孝新生. No upstream dictionary this app is built from
has the word -- not rime-ice, not McBopomofo, not CC-CEDICT -- so the decoder could only
assemble it from 忠孝 + 新生, and that path loses to 中小新生 on frequency *and* on context,
because 孝新 is a pair no ordinary text contains. The same was true of 忠孝敦化, 南京復興,
中山國中, 臺北市 and hundreds of 里 and street names. Scoring constants cannot fix a missing
word; only the word list can.

WHAT GOES IN
Every source is Taiwan government open data (政府資料開放授權條款, attribution required; see
README.md), and every name is a place in Taipei:

  data.taipei MRT stations   every station of the Taipei Metro network, with and without 站
  MOI 全國路名資料            every road name the household registry knows in 臺北市
  MOI 村里戶籍統計            every 里, from the monthly village report
  MOI 地名譯寫資料            districts, 里, streets, settlements, mountains, landmarks --
                              each with its official Hanyu Pinyin romanisation
  Chunghwa Post 路街/村里     official romanisations of road and village names

The Metro network is taken whole, including stations in New Taipei: "a Taipei MRT station" is
how people name them, and 板橋 or 新莊 is typed by the same people for the same trips.

HOW A READING IS CHOSEN
A name is useless under the wrong reading -- 重慶 must be `chong qing`, not `zhong qing`, or the
entry is unreachable by anyone who spells it right. So a reading is never guessed:

 1. McBopomofo's own entry for the whole name, when it has exactly one.
 2. An official romanisation that spells the *whole* name (or its whole leading part, when the
    tail is translated: 忠孝東路 is `Zhongxiao E. Rd.`). Only here may a mainland character
    reading be admitted, because only here is there direct evidence of how the place is said:
    McBopomofo reads 墘 only as `qi`, while every official spelling of 港墘 says `Gangqian`.
    Where this disagrees with step 1 the name gets *both* readings -- someone types each.
 3. Otherwise each character starts with its Taiwan readings from BPMFBase, and is narrowed by
    a romanisation prefix, then by McBopomofo phrases inside the name (重慶 in 重慶南路, and
    in a second pass by names already settled: 頂埔 settles 頂埔站), then by rime-ice phrases,
    which may only choose *among* Taiwan readings, never add one.
 4. Last, a character whose Taiwan usage is at least 90% one reading takes it: 景 is `jing` in
    essentially every Taiwan word. 廈 (`xia` in 廈門, `sha` in 大廈) is not settled this way.
 5. A name still ambiguous after that is dropped and listed -- never shipped with a guess.
    Five are, at the time of writing: 布埔街 廈新街 景尾街 萬樂街 山崎尾.

Romanisations that are not Hanyu Pinyin (淡水 `Tamsui`, 忠孝敦化 `Dunhwa`) or that translate the
whole name (動物園 `Taipei Zoo`) match nothing and are ignored rather than trusted.

Names written with 臺 are entered with 台 too, since that is how they are typed: phrase.occ has
24,111 台灣 and no 臺灣, and the Metro itself signs 台北車站.

HOW MUCH A NAME WEIGHS
Its own frequency in Taiwan text, counted the way McBopomofo counts phrase.occ -- every
occurrence of the string -- over 2.4G characters: PTT, Taiwan's largest forum, and four shards of
the filtered Traditional Common Crawl from September 2025, minus pages on .hk/.mo/.cn/.sg/.my
hosts, which write a different vocabulary in the same characters.

That count is put on phrase.occ's scale by one factor: the median, over the 16k words both
corpora counted at least 50 times, of phrase.occ count / corpus count. One corpus occurrence
comes out at 0.0094 of a phrase.occ count. So 忠孝新生 weighs what its 512 sightings say,
beside 忠孝 and 中小 measured the same way, with no per-kind estimate and no hand-set cap. A name
the corpus never contains (439 of them, mostly 里) gets the one-occurrence floor every
McBopomofo word gets.

The counting takes a few minutes and is cached in <work>/taipei/corpus_counts.tsv; it needs
pyahocorasick and pyarrow (`pip install --target <work>/pylib pyahocorasick pyarrow`, then run
with PYTHONPATH=<work>/pylib).
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import re
import sys
import urllib.request
import zipfile
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bopomofo  # noqa: E402

MOI = "https://opdadm.moi.gov.tw/api/v1/no-auth/resource/api/dataset/"

SOURCES = {
    # data.gov.tw dataset 131326, 臺北捷運路線車站資料服務.
    "mrt.csv": "https://data.taipei/api/dataset/8bf00fa8-86a5-437e-b5c7-9bc0fe0e2971/"
               "resource/e3c0e67f-5916-405f-ad9a-41f52a65c2d2/download",
    # data.gov.tw dataset 35321, 全國路名資料, the 115 (2026) release.
    "roads115.csv": MOI + "E2EDC47D-2D3F-4EB1-878A-4DEB6160FD4C/resource/"
                          "EB1B8C49-5C2A-42E0-8CC3-6B69B37DA66F/download",
    # data.gov.tw dataset 7064, 地名譯寫資料, the 2025 release.
    "placenames.csv": MOI + "4FECC3B1-4E35-49A5-BB46-C49A19B5B1D3/resource/"
                            "82C14CCA-2531-402D-B7AC-66DF8355E154/download",
    # data.gov.tw dataset 152276, 中華郵政路街中英對照文字檔.
    "roads_post.ods": "https://www.post.gov.tw/post/download/"
                      "%E4%B8%AD%E8%8B%B1%E6%96%87%E8%A1%97%E8%B7%AF%E5%90%8D%E7%A8%B1"
                      "%E5%B0%8D%E7%85%A7%E6%AA%94.ods",
    # Chunghwa Post's 村里文字巷中英對照 file, linked from its address-translation page.
    "village_post.txt": "https://www.post.gov.tw/post/internet/Postal/village.txt",
}

# The Taiwan text the names are counted in. PTT is Taiwan's largest forum; the Common Crawl
# shards are the filtered Traditional subset of the September 2025 crawl. Both are fetched by
# fetch_corpus() into <work>/corpus.
HF = "https://huggingface.co/datasets/"
CORPUS_PTT = ("ptt.json", HF + "yuhuanstudio/PTT-pretrain-zhtw/resolve/main/ppt_pretrain.json")
CORPUS_CC = [
    (f"2025_38_C4_Traditional_Chinese-0000{i}-of-00008.parquet",
     HF + "jed351/Traditional-Chinese-Common-Crawl-Filtered/resolve/main/2025_38/"
          f"2025_38_C4_Traditional_Chinese-0000{i}-of-00008.parquet")
    for i in range(1, 5)
]

# The monthly village report (data.gov.tw 77140), 2025-06. Paged JSON; pinned to one month so a
# rebuild is reproducible -- 里 are merged and renamed rarely, and a newer month is one edit.
VILLAGE_API = "https://www.ris.gov.tw/rs-opendata/api/v1/datastore/ODRP010/11406?page={}"

CITY = "臺北市"
MAX_WORD_LEN = 12  # build_pinyin_dict.MAX_WORD_LEN; longer entries would be dropped there anyway.

# A landmark name found in this many counties is a kind of place, not a place: 派出所,
# 活動中心, 消防隊. Applied to landmarks only -- 中正里 recurs across Taiwan and is still a 里
# in Taipei.
GENERIC_COUNTIES = 5

KINDS = ("station", "district", "village", "road", "settlement", "nature", "landmark")

MOI_KINDS = {
    "街道": "road",
    "聚落": "settlement",
    "自然地理實體": "nature",
    "具有地標意義公共設施": "landmark",
}

HAN = re.compile(r"[\u4e00-\u9fff]+")


def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


def fetch(work: str, name: str, url: str) -> str:
    path = os.path.join(work, name)
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return path
    log(f"  fetching {name}")
    os.makedirs(work, exist_ok=True)
    with urllib.request.urlopen(url, timeout=180) as r, open(path, "wb") as f:
        f.write(r.read())
    return path


def fetch_villages(work: str) -> str:
    path = os.path.join(work, "villages.tsv")
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return path
    log("  fetching villages")
    rows, page, pages = [], 1, 1
    while page <= pages:
        with urllib.request.urlopen(VILLAGE_API.format(page), timeout=120) as r:
            data = json.load(r)
        pages = int(data["totalPage"])
        rows += data["responseData"]
        page += 1
    with open(path, "w", encoding="utf-8") as f:
        for row in rows:
            if row["site_id"].startswith(CITY):
                f.write(f"{row['site_id']}\t{row['village']}\n")
    return path


# --- names -----------------------------------------------------------------------------------


def clean(name: str) -> str | None:
    name = name.strip().replace("\u3000", "")
    if not HAN.fullmatch(name) or not 2 <= len(name) <= MAX_WORD_LEN:
        return None
    return name


def collect(paths: dict) -> tuple[dict, dict, dict]:
    """(name -> kind, name -> [Taipei romanisations], name -> [romanisations elsewhere])."""
    kinds: dict[str, str] = {}
    romans: dict[str, list] = defaultdict(list)
    # The same name romanised in another county. Consulted only when Taipei's own rows say
    # nothing: 頂埔 station has no Taipei row, and `Dingpu` from four other counties is what
    # settles 埔 as `pu`.
    elsewhere: dict[str, list] = defaultdict(list)

    def add(name: str | None, kind: str) -> None:
        # First kind wins, and sources are read most-specific first: 忠孝新生 is a station
        # before it is a landmark, and the kind is what sets its weight.
        if name and name not in kinds:
            kinds[name] = kind

    text = open(paths["mrt.csv"], encoding="utf-8-sig").read()
    for station in re.findall(r"\{\d+,'\w+','([^']+)',\}", text):
        # 廣慈/奉天宮站 is two names for one stop; 台北101/世貿站 keeps only 世貿, as the
        # digits are not typed through pinyin.
        for part in station.split("/"):
            bare = part[:-1] if part.endswith("站") and not part.endswith("車站") else part
            add(clean(bare), "station")
            add(clean(bare + "站") if bare != part else None, "station")

    places = list(csv.DictReader(open(paths["placenames.csv"], encoding="utf-8-sig")))
    counties: dict[str, set] = defaultdict(set)
    for row in places:
        counties[row["PlaceName"].strip()].add(row["County"])
    for row in places:
        if row["County"] != CITY:
            other = clean(row["PlaceName"])
            if other and row["ChinesePhonetic"].strip():
                elsewhere[other].append(row["ChinesePhonetic"].strip())
            continue
        name = clean(row["PlaceName"])
        if not name:
            continue
        if row["ChinesePhonetic"].strip():
            romans[name].append(row["ChinesePhonetic"].strip())
        if row["Type"] == "行政區域":
            add(name, "village" if name.endswith("里") else "district")
        elif row["Type"] in MOI_KINDS:
            kind = MOI_KINDS[row["Type"]]
            if kind == "landmark" and len(counties[row["PlaceName"].strip()]) >= GENERIC_COUNTIES:
                continue
            add(name, kind)

    for line in open(paths["villages.tsv"], encoding="utf-8"):
        site, village = line.rstrip("\n").split("\t")
        add(clean(site[len(CITY):]), "district")
        add(clean(village), "village")

    for line in open(paths["roads115.csv"], encoding="utf-8-sig"):
        parts = line.rstrip("\n").split(",")
        if len(parts) == 3 and parts[0] == CITY:
            add(clean(parts[1][len(CITY):]), "district")
            add(clean(parts[2]), "road")

    add(CITY, "district")

    # 臺 is the official form and 台 the written one: phrase.occ has 24,111 台灣 and no 臺灣, and
    # the Metro itself signs 台北車站. A name the registry spells with 臺 is typed with 台, so
    # both are entered; the 臺 form stays for the users and documents that keep it.
    for name, kind in list(kinds.items()):
        if "臺" in name:
            add(name.replace("臺", "台"), kind)
            if name in romans:
                romans[name.replace("臺", "台")] = romans[name]

    # Official romanisations from the post office, for the roads and 里 named above.
    content = zipfile.ZipFile(paths["roads_post.ods"]).read("content.xml").decode("utf-8")
    for row in re.findall(r"<table:table-row[^>]*>(.*?)</table:table-row>", content, re.S):
        cells = re.findall(r"<text:p>(.*?)</text:p>", row)
        if len(cells) == 2 and cells[0] in kinds:
            romans[cells[0]].append(cells[1])
    with open(paths["village_post.txt"], encoding="utf-8-sig") as f:
        for row in csv.reader(f):
            if len(row) == 2 and row[0] in kinds:
                romans[row[0]].append(row[1])
    return kinds, romans, elsewhere


# --- readings ------------------------------------------------------------------------------


def taiwan_char_readings(path: str) -> dict:
    out: dict[str, list] = defaultdict(list)
    for line in open(path, encoding="utf-8"):
        parts = line.split()
        if len(parts) < 2 or line.startswith("#") or len(parts[0]) != 1:
            continue
        syl = bopomofo.syllable(parts[1])
        if syl and syl not in out[parts[0]]:
            out[parts[0]].append(syl)
    return out


def taiwan_phrases(path: str) -> dict:
    """word -> [readings], from BPMFMappings, multi-character words only."""
    out: dict[str, list] = defaultdict(list)
    for line in open(path, encoding="utf-8"):
        parts = line.split()
        if len(parts) < 3 or line.startswith("#") or len(parts) - 1 != len(parts[0]):
            continue
        syls = bopomofo.reading(parts[1:])
        if syls and syls not in out[parts[0]]:
            out[parts[0]].append(syls)
    return out


def rime_char_readings(path: str) -> dict:
    """
    char -> [readings] from rime-ice's per-character table.

    Mainland readings, so never a candidate on their own -- see [reading_for]. They exist for
    the handful of place-name characters whose local reading McBopomofo does not list: 墘 is
    only `qi` there, while every official romanisation of 港墘 says `Gangqian`.
    """
    out: dict[str, list] = defaultdict(list)
    body = False
    for line in open(path, encoding="utf-8"):
        if not body:
            body = line.strip() == "..."
            continue
        parts = line.rstrip("\n").split("\t")
        if len(parts) >= 2 and len(parts[0]) == 1 and parts[1] not in out[parts[0]]:
            out[parts[0]].append(parts[1])
    return out


def dominant_readings(tw: dict, occ: dict) -> dict:
    """
    char -> its reading when Taiwan text overwhelmingly uses one, from McBopomofo's phrases.

    Each phrase votes for the readings it gives its characters, weighted by its phrase.occ
    count. A reading carrying [DOMINANT_SHARE] of a character's votes is the one a name will
    use unless something says otherwise: 景 is `jing` in essentially every Taiwan word, and the
    `ying` BPMFBase also lists is a literary reading no street is named with. Characters whose
    readings genuinely split -- 廈 is `xia` in 廈門 and `sha` in 大廈 -- stay ambiguous, and
    names that depend on them are dropped rather than guessed.
    """
    votes: dict[str, dict] = defaultdict(lambda: defaultdict(int))
    for word, readings in tw.items():
        weight = occ.get(word, 0) + 1
        for syls in readings:
            for ch, syl in zip(word, syls):
                votes[ch][syl] += weight
    out = {}
    for ch, by in votes.items():
        syl, n = max(by.items(), key=lambda kv: kv[1])
        if n >= DOMINANT_SHARE * sum(by.values()):
            out[ch] = syl
    return out


# Share of a character's Taiwan usage one reading must carry to break a tie: ten to one.
DOMINANT_SHARE = 0.9


def rime_phrases(paths: list, wanted: set) -> dict:
    """word -> [readings] from rime-ice, only for substrings some name contains."""
    out: dict[str, list] = defaultdict(list)
    for path in paths:
        body = False
        for line in open(path, encoding="utf-8"):
            if not body:
                body = line.strip() == "..."
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 2 or parts[0] not in wanted:
                continue
            syls = parts[1].split()
            if len(syls) == len(parts[0]) and syls not in out[parts[0]]:
                out[parts[0]].append(syls)
    return out


def roman_tokens(roman: str) -> list[str]:
    """`Chang'an E. Rd.` -> ['changan', 'e', 'rd']."""
    return [t for t in (re.sub(r"[^a-z]", "", w.lower()) for w in roman.split()) if t]


def roman_constraint(cands: list, roman: str) -> list | None:
    """
    Narrows [cands] to the readings that spell the longest possible prefix of [roman].

    None when no prefix matches at all -- the romanisation is then not Hanyu Pinyin (Tamsui,
    Dunhwa) or translates the whole name (Taipei Zoo), and says nothing about the reading.
    """
    tokens = roman_tokens(roman)
    targets = {"".join(tokens[:j]) for j in range(1, len(tokens) + 1)}
    best_k = 0
    best: list | None = None
    # Depth-first over characters; names are short and readings per character few.
    def walk(i: int, spelled: str, chosen: list) -> None:
        nonlocal best_k, best
        if spelled in targets and i > best_k:
            best_k, best = i, [[c] for c in chosen]
        elif spelled in targets and i == best_k and best is not None:
            for slot, c in zip(best, chosen):
                if c not in slot:
                    slot.append(c)
        if i == len(cands):
            return
        for syl in cands[i]:
            nxt = spelled + syl
            if any(t.startswith(nxt) for t in targets):
                walk(i + 1, nxt, chosen + [syl])
    walk(0, "", [])
    if best is None:
        return None
    return best + [list(c) for c in cands[best_k:]]


def phrase_constraint(name: str, cands: list, phrases: dict) -> list:
    """Restricts ambiguous characters to readings a phrase inside [name] gives them."""
    proposals: list[set] = [set() for _ in name]
    for i in range(len(name)):
        for j in range(i + 2, len(name) + 1):
            if i == 0 and j == len(name):
                continue  # the name itself: what is being decided, not evidence for it
            for syls in phrases.get(name[i:j], ()):
                if all(s in cands[i + k] for k, s in enumerate(syls)):
                    for k, s in enumerate(syls):
                        proposals[i + k].add(s)
    return [
        [s for s in c if s in p] if len(c) > 1 and p else c
        for c, p in zip(cands, proposals)
    ]


def full_roman_reading(name: str, cands: list, cn_chars: dict, romans: list) -> list | None:
    """
    The one reading an official romanisation spells for *every* character of [name], or None.

    Mainland readings are admitted here, and only here, because a romanisation is direct
    evidence of how the place is said: 墘 is only `qi` in McBopomofo, while every official
    spelling of 港墘 -- and the Metro's own announcement -- says `Gangqian`.
    """
    wider = [c + [s for s in cn_chars.get(ch, ()) if s not in c] for c, ch in zip(cands, name)]
    for roman in romans:
        narrowed = roman_constraint(wider, roman)
        if narrowed is None:
            continue
        # The romanisation may cover only a prefix -- `Gangqian Rd.` translates 路 rather than
        # spelling it -- and the characters it does not reach keep their Taiwan readings only.
        covered = covered_prefix(narrowed, roman)
        head = [c[0] for c in narrowed[:covered]] if covered else None
        tail = cands[covered:]
        if head and all(len(c) == 1 for c in narrowed[:covered]) and all(len(c) == 1 for c in tail):
            return head + [c[0] for c in tail]
    return None


def covered_prefix(narrowed: list, roman: str) -> int:
    """How many leading characters of a [roman_constraint] result the romanisation spelled."""
    target = "".join(roman_tokens(roman))
    spelled, covered = "", 0
    for i, c in enumerate(narrowed):
        if len(c) != 1 or not target.startswith(spelled + c[0]):
            break
        spelled += c[0]
        covered = i + 1
    return covered if spelled and any(
        "".join(roman_tokens(roman)[:j]) == spelled for j in range(1, len(roman_tokens(roman)) + 1)
    ) else 0


def reading_for(name: str, chars: dict, cn_chars: dict, dominant: dict, tw: dict,
                phrases: dict, cn: dict, romans: list, stats: dict) -> list:
    """
    The Taiwan readings of [name]: usually one, none when the evidence does not settle it.

    Strongest evidence first, and each step may only narrow what the previous ones left:
    McBopomofo's own entry, an official romanisation, a Taiwan phrase inside the name, a
    mainland phrase inside it, and last a character's dominant Taiwan reading.

    A second reading is added only where an official romanisation spells the whole name
    differently from McBopomofo's entry -- both are how someone says it, and a name reachable
    under only one of them is lost to whoever types the other.
    """
    cands = [list(chars.get(ch, ())) for ch in name]
    if not all(cands):
        stats["no reading"] += 1
        return []
    official = full_roman_reading(name, cands, cn_chars, romans)
    if name in tw and len(tw[name]) == 1:
        out = [tw[name][0]]
        if official and official != out[0]:
            stats["second reading from romanisation"] += 1
            out.append(official)
        return out
    if official:
        return [official]
    for roman in romans:
        narrowed = roman_constraint(cands, roman)
        if narrowed is not None:
            cands = narrowed
    cands = phrase_constraint(name, cands, phrases)
    cands = phrase_constraint(name, cands, cn)
    cands = [[dominant[ch]] if len(c) > 1 and dominant.get(ch) in c else c
             for c, ch in zip(cands, name)]
    if any(len(c) != 1 for c in cands):
        stats["ambiguous"] += 1
        stats.setdefault("ambiguous names", []).append(name)
        return []
    return [[c[0] for c in cands]]


def check_against_official(name: str, reading: list, romans: list, stats: dict) -> None:
    """Counts names whose chosen reading disagrees with a Hanyu romanisation of them."""
    for roman in romans:
        tokens = roman_tokens(roman)
        if not tokens:
            continue
        spelled = "".join(reading)
        prefixes = {"".join(tokens[:j]) for j in range(1, len(tokens) + 1)}
        if any(spelled.startswith(p) for p in prefixes):
            stats["agrees with official"] += 1
            return
    if romans:
        stats["official not Hanyu"] += 1


# --- weights ---------------------------------------------------------------------------------


def occurrences(path: str) -> dict:
    out: dict[str, int] = {}
    for line in open(path, encoding="utf-8"):
        parts = line.split()
        if len(parts) >= 2 and parts[1].isdigit():
            out[parts[0]] = max(out.get(parts[0], 0), int(parts[1]))
    return out


# Words that calibrate the corpus against phrase.occ: every multi-character entry McBopomofo
# counted at least this often. Below it the phrase.occ count is too noisy to calibrate against.
CALIBRATION_MIN = 50

# Top-level domains whose Traditional Chinese is not Taiwan's. Common Crawl's Traditional subset
# is roughly a twentieth Hong Kong, and Hong Kong writes a different vocabulary with the same
# characters -- the frequencies this builds are meant to be Taiwan's.
NOT_TAIWAN = ("hk", "mo", "cn", "sg", "my")


def _count_job(job: tuple) -> dict:
    """Substring counts of every pattern in one corpus file. Runs in a worker process."""
    import ahocorasick
    kind, path, patterns = job
    automaton = ahocorasick.Automaton()
    for p in patterns:
        automaton.add_word(p, p)
    automaton.make_automaton()
    counts: dict[str, int] = defaultdict(int)
    chars = 0

    def feed(text: str) -> None:
        nonlocal chars
        chars += len(text)
        for _end, p in automaton.iter(text):
            counts[p] += 1

    if kind == "ptt":
        # One JSON array, one document per `"text":` line; parsed line by line because
        # json.load on 850 MB would not fit beside the other workers.
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line.startswith('"text":'):
                    feed(json.loads(line[len('"text":'):].rstrip(",")))
    else:
        import pyarrow.parquet as pq
        from urllib.parse import urlparse
        for batch in pq.ParquetFile(path).iter_batches(batch_size=5000, columns=["url", "text"]):
            for url, text in zip(batch.column("url").to_pylist(), batch.column("text").to_pylist()):
                host = urlparse(url or "").hostname or ""
                if text and host.rsplit(".", 1)[-1] not in NOT_TAIWAN:
                    feed(text)
    counts["\0chars"] = chars
    return dict(counts)


def corpus_counts(work: str, patterns: set) -> tuple[dict, int]:
    """
    How often each pattern occurs in the Taiwan text corpus, and the corpus size in characters.

    Counted as McBopomofo counts phrase.occ -- every occurrence of the string, not segmented
    words -- so the two are the same measurement on different text and can be put on one scale.
    Cached, because it takes minutes; the cache is keyed by the pattern set.
    """
    cache = os.path.join(work, "taipei", "corpus_counts.tsv")
    if os.path.exists(cache):
        cached = {}
        with open(cache, encoding="utf-8") as f:
            for line in f:
                k, v = line.rstrip("\n").split("\t")
                cached[k] = int(v)
        if patterns <= cached.keys():
            return cached, cached["\0chars"]
    jobs = [("ptt", os.path.join(work, "corpus", CORPUS_PTT[0]), sorted(patterns))]
    jobs += [("cc", os.path.join(work, "corpus", name), sorted(patterns)) for name, _ in CORPUS_CC]
    for _kind, path, _ in jobs:
        if not os.path.exists(path):
            raise SystemExit(f"missing corpus file {path}; fetch it first (see CORPUS_*)")
    log(f"  counting {len(patterns)} strings over {len(jobs)} corpus files")
    import multiprocessing
    total: dict[str, int] = defaultdict(int)
    with multiprocessing.Pool(COUNT_WORKERS) as pool:
        for part in pool.imap_unordered(_count_job, jobs):
            for k, v in part.items():
                total[k] += v
            log(f"    {part['\0chars'] / 1e6:.0f}M characters counted")
    for p in patterns:
        total.setdefault(p, 0)
    with open(cache, "w", encoding="utf-8") as f:
        for k in sorted(total):
            f.write(f"{k}\t{total[k]}\n")
    return dict(total), total["\0chars"]


# Parallel counting workers. Each holds one parquet batch stream (~1.2 GB resident); three fit
# beside the rest of a 7 GB box.
COUNT_WORKERS = 3


def calibration(occ: dict, counts: dict) -> tuple[float, int]:
    """
    The factor that puts a corpus count on phrase.occ's scale, and how many words set it.

    The median, over words both corpora counted well, of phrase.occ count / corpus count. A median
    of ratios rather than a ratio of totals, because the two corpora differ in topic and a few
    very common words that one of them over-represents would otherwise decide it alone.
    """
    ratios = sorted(occ[w] / counts[w] for w in occ
                    if len(w) >= 2 and occ[w] >= CALIBRATION_MIN and counts.get(w, 0) >= CALIBRATION_MIN)
    return ratios[len(ratios) // 2], len(ratios)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--work", default="../zhwork",
                    help="directory holding McBopomofo/rime-ice sources (build_pinyin_dict --work)")
    ap.add_argument("--out", default="tools/places/taipei.tsv")
    args = ap.parse_args()

    cache = os.path.join(args.work, "taipei")
    paths = {n: fetch(cache, n, u) for n, u in SOURCES.items()}
    paths["villages.tsv"] = fetch_villages(cache)
    for name, url in [CORPUS_PTT, *CORPUS_CC]:
        fetch(os.path.join(args.work, "corpus"), name, url)

    kinds, romans, elsewhere = collect(paths)
    log(f"names: {len(kinds)} "
        + " ".join(f"{k}={sum(1 for v in kinds.values() if v == k)}" for k in KINDS))

    chars = taiwan_char_readings(os.path.join(args.work, "BPMFBase.txt"))
    tw = taiwan_phrases(os.path.join(args.work, "BPMFMappings.txt"))
    wanted = {n[i:j] for n in kinds for i in range(len(n)) for j in range(i + 2, len(n) + 1)}
    cn = rime_phrases([os.path.join(args.work, f) for f in ("base.dict.yaml", "ext.dict.yaml")],
                      wanted)
    cn_chars = rime_char_readings(os.path.join(args.work, "8105.dict.yaml"))

    occ = occurrences(os.path.join(args.work, "phrase.occ"))
    dominant = dominant_readings(tw, occ)
    calibrators = {w for w, n in occ.items() if len(w) >= 2 and n >= CALIBRATION_MIN}
    counts, corpus_chars = corpus_counts(args.work, set(kinds) | {n.replace("臺", "台") for n in kinds}
                                  | calibrators)
    factor, used = calibration(occ, counts)
    log(f"corpus: {corpus_chars / 1e9:.2f}G characters; 1 corpus occurrence = {factor:.4f} phrase.occ "
        f"counts (median over {used} words both counted at least {CALIBRATION_MIN} times)")

    # Two passes. The second treats every name the first one settled as a phrase, so a name
    # built from another inherits its reading: 頂埔站 has no romanisation of its own, but 頂埔
    # does, and that is how anyone would read the station.
    order = sorted(kinds, key=lambda n: (KINDS.index(kinds[n]), n))
    resolved: dict[str, list] = {}
    stats: dict = defaultdict(int)
    for final in (False, True):
        stats = defaultdict(int)
        phrases = dict(tw)
        for name, readings in resolved.items():
            phrases.setdefault(name, readings)
        for name in order:
            official = romans.get(name) or elsewhere.get(name, [])
            readings = reading_for(name, chars, cn_chars, dominant, tw, phrases, cn, official,
                                   stats)
            if readings:
                resolved[name] = readings
                if final:
                    check_against_official(name, readings[-1], official, stats)
    # The weight is the name's own count, on phrase.occ's scale, to two decimals: the builder
    # applies the same one-occurrence floor it gives every McBopomofo word.
    rows = [(n, " ".join(r), kinds[n], f"{counts.get(n, 0) * factor:.2f}")
            for n in order if n in resolved for r in resolved[n]]
    unseen = sum(1 for n in resolved if counts.get(n, 0) == 0)
    log(f"counts: {unseen} of {len(resolved)} names never occur in the corpus")

    log(f"readings: {len(resolved)} names kept, {stats['ambiguous']} ambiguous, "
        f"{stats['no reading']} without a Taiwan reading; "
        f"{stats['agrees with official']} checked against an official romanisation, "
        f"{stats['official not Hanyu']} whose romanisation is not Hanyu, "
        f"{stats['second reading from romanisation']} given a second reading by romanisation")
    if stats.get("ambiguous names"):
        log("  dropped as ambiguous: " + " ".join(stats["ambiguous names"]))

    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        f.write("# Generated by tools/build_taipei_places.py -- edit that, not this.\n")
        f.write("# name\treading\tkind\tcorpus count, rescaled onto phrase.occ\n")
        for row in rows:
            f.write("\t".join(map(str, row)) + "\n")
    log(f"wrote {args.out}: {len(rows)} entries for {len(resolved)} names")


if __name__ == "__main__":
    main()
