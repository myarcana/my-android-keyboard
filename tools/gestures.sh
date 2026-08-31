#!/bin/zsh
# The gesture bank: collect it, pull it, score it.
#
#   tools/gestures.sh lab       build, install, open the Gesture Lab with our IME selected
#   tools/gestures.sh pull      copy the phone's bank into data/gesture-bank.jsonl
#   tools/gestures.sh stats     what is in the local bank
#   tools/gestures.sh analyse   replay every sample and sweep the thresholds
#
# The pull merges rather than overwrites, keyed on each record's id. Sessions accumulate over
# weeks and the phone's copy is the only one that has the newest ones, but the repo's copy is the
# only one that survives an uninstall -- so neither is the master and both have to be kept.

set -e

export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
export ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
ADB="$ANDROID_HOME/platform-tools/adb"
PKG=com.offlinekeyboard.ime
IME=$PKG/.KeyboardService
BANK=data/gesture-bank.jsonl

# Counts the bank by label. Defined once, in a variable, because it is wanted from inside a
# command substitution where a heredoc would be more trouble than it is worth.
# Session lines share the file with gesture lines and have no "intent"; every reader here wants
# the gestures, so the filter lives next to the load in each of them.
SUMMARISE='
import json, sys, collections
all_rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
rows = [r for r in all_rows if r.get("kind") != "session"]
runs = len(all_rows) - len(rows)
by = collections.Counter(r["intent"] for r in rows)
print(f"{len(rows)} gestures in {runs} runs: " + ", ".join(f"{n} {k.lower()}" for k, n in sorted(by.items())))
'

cd "$(dirname "$0")/.."

case "${1:-help}" in

lab)
    ./gradlew --quiet assembleDebug

    # ColorOS blocks installs behind a confirmation screen unless the verifier is off.
    if [[ "$($ADB shell settings get global verifier_verify_adb_installs | tr -d '\r')" != "0" ]]; then
        echo "note: package verifier is on -- installs will hang. Disabling it."
        $ADB shell settings put global verifier_verify_adb_installs 0
        $ADB shell settings put global package_verifier_user_consent -1
    fi

    $ADB shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    $ADB shell wm dismiss-keyguard >/dev/null 2>&1 || true

    $ADB install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
    # -S force-stops the package, which drops the IME selection, so re-select it afterwards.
    $ADB shell am start -S -n $PKG/.GestureLabActivity >/dev/null
    $ADB shell ime enable "$IME" >/dev/null 2>&1 || true
    $ADB shell ime set "$IME" >/dev/null
    echo "Gesture Lab is open. Type the passage straight through; every gesture is filed"
    echo "under the token it was aimed at. It deals its own passages from assets/passages_en.txt"
    echo "and remembers where it got to, so it collects just as well away from this machine."
    echo "The bank stays in internal storage until it is pulled: run tools/gestures.sh pull"
    echo "whenever the phone is next on the cable."

    # Says up front whether the last session ever reached the repository, because the way this
    # data gets lost is a session that was recorded, enjoyed, and never pulled.
    on_phone=$($ADB exec-out run-as $PKG cat files/gesture-bank.jsonl 2>/dev/null | grep -c . || true)
    in_repo=0
    [[ -f "$BANK" ]] && in_repo=$(grep -c . "$BANK")
    if [[ "$on_phone" -gt "$in_repo" ]]; then
        echo "note: $((on_phone - in_repo)) gestures on the phone are not in the repository yet."
        echo "      run tools/gestures.sh pull when you are done."
    else
        echo "$in_repo gestures saved and committed."
    fi
    ;;

pull)
    mkdir -p data
    tmp=$(mktemp)
    got=0
    # Internal storage is where the app appends and is always the newest. The copy under
    # Android/data is whatever the lab's Export button last wrote, and exists for the case
    # run-as cannot cover -- a release build, or a ROM that blocks it. Both are read and the
    # merge below sorts it out by id, because on a phone where run-as is blocked the second is
    # all there is.
    for source in \
        "run-as $PKG cat files/gesture-bank.jsonl" \
        "cat /sdcard/Android/data/$PKG/files/gesture-bank.jsonl"
    do
        part=$(mktemp)
        $ADB exec-out ${=source} > "$part" 2>/dev/null
        # A missing file is not an error here -- most phones will have two of these three -- but
        # it is also not empty: adb prints cat's complaint onto stdout, so "did anything come
        # back" would count "No such file or directory" as a bank. The first character settles
        # it, since every line of a real one starts a JSON object.
        if [[ -s "$part" && "$(head -c 1 "$part")" == "{" ]]; then
            echo "read $(grep -c . "$part") lines from: $source"
            cat "$part" >> "$tmp"
            got=1
        fi
        rm -f "$part"
    done
    if [[ "$got" != "1" ]]; then
        echo "nothing to pull -- no bank on the device, or run-as is blocked and nothing exported"
        rm -f "$tmp"
        exit 1
    fi

    python3 - "$tmp" "$BANK" <<'PY'
import json, sys, os
incoming, bank = sys.argv[1], sys.argv[2]

def read(path):
    if not os.path.exists(path):
        return []
    out = []
    for line in open(path):
        line = line.strip()
        if not line:
            continue
        try:
            out.append(json.loads(line))
        except json.JSONDecodeError:
            pass
    return out

# Two kinds of line share this file and they do not merge the same way, so the key has to say
# which kind it is. Keying on the id alone let a session and a gesture collide in principle, and
# in practice did something worse -- see the session rule below.
def key(r):
    return (r.get("kind", "gesture"), r["id"])

before = read(bank)
merged = {key(r): r for r in before}
added = 0
kept = 0
grew = 0
for r in read(incoming):
    k = key(r)
    old = merged.get(k)
    if old is None:
        merged[k] = r
        added += 1
        continue
    if k[0] == "session":
        # A session line is the one thing here that is legitimately rewritten: it is written when
        # a run starts, with nothing typed yet, and again when it ends. Both carry the same id, so
        # under the gesture rule the *empty* one won and every finished run was archived as though
        # nothing had been typed in it. A run only ever grows, so the longer transcript wins.
        if len(r.get("actual", "")) > len(old.get("actual", "")):
            merged[k] = r
            grew += 1
        continue
    # A gesture never legitimately changes after it is written, with one exception: a label can
    # be withdrawn afterwards, in the lab or by hand in this file. So the copy already here
    # wins, and the only thing an incoming copy can add is a void the local one is missing.
    # Letting incoming win outright silently reverted every withdrawal the moment the phone --
    # which has never seen them -- was read again.
    if old.get("void") is None and r.get("void") is not None:
        merged[k] = r
    elif old != r:
        kept += 1

rows = sorted(merged.values(), key=lambda r: (r["at"], r.get("kind", "gesture") != "session"))
with open(bank, "w") as f:
    for r in rows:
        f.write(json.dumps(r, separators=(",", ":"), ensure_ascii=False) + "\n")

sessions = sum(1 for r in rows if r.get("kind") == "session")
print(f"{bank}: {len(rows) - sessions} gestures in {sessions} runs (+{added} new)")
if grew:
    print(f"  {grew} runs had more typing in them than the archived copy")
if kept:
    print(f"  {kept} incoming copies differed from the archived ones and were ignored")
PY
    rm -f "$tmp"

    # A pull that is not committed has not saved anything. The phone's copy dies with the app,
    # and an untracked file in the working tree is one `git clean` away from gone -- so the
    # commit is part of the pull rather than something to remember afterwards.
    #
    # This deliberately does NOT use git's partial-commit form (`git commit -- <path>`), which
    # would be the obvious way to commit one file regardless of what else is staged. That form
    # builds a temporary index, and this repo's commit-msg hook -- which walks every object in
    # the repository -- cannot resolve a reference in it and aborts the commit. So instead the
    # bank is staged and the index is checked: if anything else is staged, nothing is committed
    # and it says so, rather than sweeping unrelated work into a data commit.
    if git rev-parse --git-dir >/dev/null 2>&1; then
        if [[ -z "$(git status --porcelain -- "$BANK")" ]]; then
            echo "already committed, nothing new"
        else
            git add "$BANK"
            staged=$(git diff --cached --name-only)
            if [[ "$staged" != "$BANK" ]]; then
                echo "NOT committed: other changes are staged as well --"
                echo "$staged" | grep -v "^$BANK$" | sed 's/^/    /'
                echo "  The bank is staged and safe. Commit or unstage those, then run pull again."
            else
                summary=$(python3 -c "$SUMMARISE" "$BANK")
                git commit -q -m "Gesture bank: $summary"
                echo "committed $(git log -1 --format=%h): $summary"
            fi
        fi
    else
        echo "WARNING: not a git repository -- the bank is NOT under version control"
    fi
    ;;

stats)
    if [[ ! -f "$BANK" ]]; then
        echo "no bank yet at $BANK -- collect some, then: tools/gestures.sh pull"
        exit 1
    fi
    python3 - "$BANK" <<'PY'
import json, sys, collections
all_rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
rows = [r for r in all_rows if r.get("kind") != "session"]
runs = [r for r in all_rows if r.get("kind") == "session"]
print(f"{len(rows)} samples in {len(runs)} runs")
for run in runs[-5:]:
    typed, want = run.get("actual", ""), run.get("intended", "")
    same = sum(1 for a, b in zip(typed, want) if a == b)
    print(f"  {run['passage']:<12} {len(typed):>4}/{len(want):<4} chars, {same} matching from the start")
by_intent = collections.Counter(r["intent"] for r in rows)
for intent, n in sorted(by_intent.items()):
    print(f"  {intent:<7} {n}")
print()
print("  key  intent   n   verdict on the build that recorded it")
grouped = collections.defaultdict(list)
for r in rows:
    grouped[(r["startKey"], r["intent"])].append(r)
for (key, intent), group in sorted(grouped.items()):
    verdicts = collections.Counter(r["verdict"] for r in group)
    detail = " ".join(f"{v}={n}" for v, n in verdicts.most_common())
    print(f"  {key:<4} {intent.lower():<8} {len(group):<3} {detail}")
wrong = [r for r in rows
         if (r["verdict"] == "FLICK") != (r["intent"] == "SYMBOL")
         or r["verdict"] not in ("FLICK", "GLIDE")]
print()
print(f"  {len(wrong)} of {len(rows)} were read wrongly by the build that recorded them")

# The glide half. Decoding and the finger-lift window are only scorable once prose passages
# have been typed, so this stays quiet until there is something to say.
def letters(s):
    return "".join(c for c in s.lower() if c.isalpha())

glides = [r for r in rows if r["intent"] == "WORD"]
decoded = [r for r in glides if r.get("decoded")]
if decoded:
    right = sum(1 for r in decoded if letters(r["decoded"]) == letters(r["expected"]))
    print()
    print(f"  {right} of {len(decoded)} glides decoded to the word asked for "
          f"({100 * right // len(decoded)}%)")
    for r in decoded:
        if letters(r["decoded"]) != letters(r["expected"]):
            print(f"    wanted {r['expected']:<12} typed {r['decoded']}")

gaps = []
for r in glides:
    path = r["path"]
    for at in r.get("strokes", []):
        if 0 < at < len(path):
            before, after = path[at - 1], path[at]
            dx, dy = after[0] - before[0], after[1] - before[1]
            gaps.append((after[2] - before[2], (dx * dx + dy * dy) ** 0.5))
if gaps:
    ms = sorted(g[0] for g in gaps)
    px = sorted(g[1] for g in gaps)
    print()
    print(f"  {len(gaps)} mid-glide finger lifts were rejoined")
    print(f"    duration ms  min {ms[0]}  median {ms[len(ms) // 2]}  max {ms[-1]}")
    print(f"    distance px  min {px[0]:.0f}  median {px[len(px) // 2]:.0f}  max {px[-1]:.0f}")
PY
    ;;

analyse|analyze)
    # An explicit path scores some other bank -- a single session, say, or an older archive.
    # It has to be absolute: Gradle runs unit tests with the module directory as their cwd.
    bank_arg=""
    if [[ -n "${2:-}" ]]; then
        bank_arg="-Dgesture.bank=$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
    fi
    # No --quiet here: it would swallow the printed report, which is the whole command.
    ./gradlew testDebugUnitTest --tests '*GestureBankReplayTest*' --rerun-tasks \
        --console=plain $bank_arg
    ;;

*)
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
    ;;
esac
