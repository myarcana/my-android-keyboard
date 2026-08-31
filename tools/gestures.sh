#!/bin/zsh
# The gesture bank: collect it, pull it, score it.
#
# The lab collects for the whole typing experience -- the tap decoder's spatial model, glide
# decoding, the flick thresholds -- so a session is worth having whatever was typed and however.
# `analyse` scores the part that needs a replay of the state machine; `tools/fit_spatial.py`
# reads the taps, needs no labels, and grows with every session.
#
#   tools/gestures.sh lab       build, install, open the Gesture Lab with our IME selected
#   tools/gestures.sh pull      copy the phone's bank into data/gesture-bank.jsonl
#   tools/gestures.sh stats     what is in the local bank, and how much of it moved
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
#
# Only pre-v6 lines carry a label at all, and only the ones somebody was *told* to make are
# evidence for flick-versus-glide -- a prose passage says which word is due, not whether the
# thumb will glide it or tap it out. Counting every labelled line under one heading is what
# made this line claim 2081 glides when the bank held 82. Everything else is ordinary typing,
# kept because the spatial fit and the transcripts both want it.
SUMMARISE='
import json, sys, collections
all_rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
rows = [r for r in all_rows if r.get("kind") != "session"]
runs = len(all_rows) - len(rows)
asked = [r for r in rows
         if "intent" in r and not str(r.get("prompt", "")).startswith("word:")]
by = collections.Counter(r["intent"] for r in asked)
parts = ", ".join(f"{n} {k.lower()}" for k, n in sorted(by.items()))
print(f"{len(rows)} gestures in {runs} runs: {len(asked)} asked for by name ({parts}), "
      f"{len(rows) - len(asked)} typing")
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
import json, sys, collections, math
all_rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
rows = [r for r in all_rows if r.get("kind") != "session"]
runs = [r for r in all_rows if r.get("kind") == "session"]
sessions = {r["id"]: r for r in runs}
print(f"{len(rows)} samples in {len(runs)} runs")
for run in runs[-5:]:
    typed, want = run.get("actual", ""), run.get("intended", "")
    same = sum(1 for a, b in zip(typed, want) if a == b)
    print(f"  {run['passage']:<14} {len(typed):>4}/{len(want):<4} chars, {same} matching from the start")

# Only a pre-v6 line carries a label, and only one that was asked for by name is evidence for
# flick-versus-glide. Everything else is typing: still wanted, but for the spatial fit and the
# transcripts rather than for the sweep.
asked = [r for r in rows if "intent" in r and not str(r.get("prompt", "")).startswith("word:")]
print()
print(f"  {len(asked)} were asked for by name and can be scored for flick-versus-glide")
for intent, n in sorted(collections.Counter(r["intent"] for r in asked).items()):
    print(f"    {intent.lower():<7} {n}")
print(f"  {len(rows) - len(asked)} are typing, kept for the spatial fit and the transcripts")

voided = [r for r in rows if r.get("void")]
if voided:
    print(f"  {len(voided)} withdrawn, kept in the file but not evidence")

# What shape are these gestures? A stationary press and a stroke across the keyboard are the
# two things this bank exists to tell apart, and the count of each is the first thing a reader
# wants -- a collecting run that produced no strokes at all has told the decoder nothing, and
# without this line it looks exactly like a good one. Measured, not labelled: a path either
# moved or it did not.
def travel(r):
    p = r["path"]
    return sum(math.hypot(p[i + 1][0] - p[i][0], p[i + 1][1] - p[i][1]) for i in range(len(p) - 1))

moved = [r for r in rows if travel(r) > 0.5]
print()
print(f"  {len(moved)} paths moved; {len(rows) - len(moved)} were a stationary press")
if moved:
    units = sorted(travel(r) / r["keyUnitPx"] for r in moved)
    print(f"    travel in key widths  median {units[len(units) // 2]:.1f}  max {units[-1]:.1f}")

# The same split per run, because that is where a barren session shows up.
print()
print("  per run:")
for run in runs:
    mine = [r for r in rows if r.get("session") == run["id"]]
    if not mine:
        continue
    m = sum(1 for r in mine if travel(r) > 0.5)
    flag = "   <- no strokes at all" if m == 0 and len(mine) > 20 else ""
    print(f"    {run['passage']:<14} {len(mine):>4} gestures, {m:>3} moved{flag}")

print()
print("  taps per key:")
taps = collections.Counter(r["startKey"] for r in rows if travel(r) <= 0.5)
line = " ".join(f"{k}={n}" for k, n in sorted(taps.items(), key=lambda kv: -kv[1]))
print("    " + line)
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
