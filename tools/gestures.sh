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
SUMMARISE='
import json, sys, collections
rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
by = collections.Counter(r["intent"] for r in rows)
print(f"{len(rows)} gestures: " + ", ".join(f"{n} {k.lower()}" for k, n in sorted(by.items())))
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
    echo "Gesture Lab is open. Swipe as asked; every gesture is filed under what it asked for."

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
    # run-as reaches internal storage on a debuggable build. If it is blocked (some vendor
    # ROMs, or a release build) fall back to whatever the lab's Export button last wrote.
    if $ADB exec-out run-as $PKG cat files/gesture-bank.jsonl > "$tmp" 2>/dev/null && [[ -s "$tmp" ]]; then
        echo "pulled via run-as"
    elif $ADB exec-out cat "/sdcard/Android/data/$PKG/files/gesture-bank.jsonl" > "$tmp" 2>/dev/null && [[ -s "$tmp" ]]; then
        echo "pulled the exported copy (tap Export in the lab to refresh it)"
    else
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

before = read(bank)
merged = {r["id"]: r for r in before}
added = 0
for r in read(incoming):
    if r["id"] not in merged:
        added += 1
    merged[r["id"]] = r

rows = sorted(merged.values(), key=lambda r: r["at"])
with open(bank, "w") as f:
    for r in rows:
        f.write(json.dumps(r, separators=(",", ":"), ensure_ascii=False) + "\n")

print(f"{bank}: {len(rows)} samples (+{added} new)")
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
rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
print(f"{len(rows)} samples")
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
