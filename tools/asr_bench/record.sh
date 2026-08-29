#!/bin/zsh
# Read each prompt aloud once. One 16 kHz mono WAV per prompt, which is what every candidate
# model expects, so nothing is resampled between recording and scoring.
#
#   tools/asr_bench/record.sh            record whatever is still missing
#   tools/asr_bench/record.sh tw03 mx01  re-record just these
#   AUDIO_DEVICE=1 tools/asr_bench/record.sh   use a different microphone
#
# Read at your normal messaging pace, in the accent you actually use. The point of the exercise
# is your voice, not a clean read: a model that wins on careful diction and loses on the way you
# really talk is the wrong model.

cd "$(dirname "$0")/../.."

DEVICE=${AUDIO_DEVICE:-0}
OUT=asr-bench/recordings
TMP=$(mktemp -d)
trap 'rm -rf $TMP' EXIT
mkdir -p $OUT

if ! command -v ffmpeg >/dev/null; then
    echo "ffmpeg not found: brew install ffmpeg" >&2
    exit 1
fi

# ffmpeg's AVFoundation input prints an Objective-C warning about Continuity Camera on every
# invocation. It is unrelated to audio capture and there is no flag that silences it, so it is
# filtered here rather than left to look like a failure.
quiet_ffmpeg() {
    ffmpeg "$@" 2>$TMP/err || true
    grep -v -e ContinuityCamera -e '^$' $TMP/err >&2 || true
}

# Peak level in dB, for spotting a take that captured nothing.
peak_db() {
    ffmpeg -hide_banner -i "$1" -af volumedetect -f null - 2>&1 |
        sed -n 's/.*max_volume: \(-*[0-9.]*\) dB.*/\1/p' | tail -1
}

total=$(( $(wc -l < tools/asr_bench/prompts.tsv) - 1 ))

echo
echo "Microphone: audio device $DEVICE. Others:  ffmpeg -f avfoundation -list_devices true -i \"\""
echo "Recording stops when you press  q.  Ctrl-C quits."
echo

tail -n +2 tools/asr_bench/prompts.tsv | while IFS=$'\t' read -r id lang text; do
    [[ -z "$id" ]] && continue
    if [[ $# -gt 0 ]]; then
        [[ " $* " == *" $id "* ]] || continue
    elif [[ -f "$OUT/$id.wav" ]]; then
        continue
    fi

    done_count=$(ls $OUT/*.wav 2>/dev/null | wc -l | tr -d ' ')
    while true; do
        echo "──────────────────────────────────────────────────────────────"
        echo "$id  [$lang]   ($done_count of $total done)"
        echo
        echo "  $text"
        echo
        printf "Enter to start… "
        read -r _ < /dev/tty
        printf "\r\033[K  ● recording — press q to stop\n"

        # Capped so a forgotten q cannot record for ever.
        quiet_ffmpeg -hide_banner -loglevel error -f avfoundation -i ":$DEVICE" \
            -ar 16000 -ac 1 -t 60 -y "$OUT/$id.wav" < /dev/tty

        if [[ ! -s "$OUT/$id.wav" ]]; then
            echo "  nothing was captured. Check microphone permission for your terminal in"
            echo "  System Settings > Privacy & Security > Microphone, then try again."
            printf "  Enter to retry… "
            read -r _ < /dev/tty
            continue
        fi

        seconds=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT/$id.wav")
        peak=$(peak_db "$OUT/$id.wav")
        printf "  %.1fs, peak %s dB" "$seconds" "$peak"

        # Two ways a take is quietly wrong, both worth catching now rather than discovering
        # them as an unexplained error rate an hour later.
        problem=""
        # Room tone rather than speech: microphone muted, or the wrong input device.
        if [[ -n "$peak" ]] && (( ${peak%.*} < -30 )); then
            problem="that sounds like silence"
        # No prompt here takes more than about eight seconds to read, so a long take means q
        # was pressed late -- or, worse, that the line got read twice. A doubled sentence
        # transcribes as a doubled sentence and ruins that prompt's score.
        elif (( ${seconds%.*} > 15 )); then
            problem="that is long for this line — was it read twice?"
        fi

        if [[ -n "$problem" ]]; then
            echo "  — $problem"
            printf "  Enter to re-record, or s to keep it… "
            read -r answer < /dev/tty
            [[ "$answer" == "s" ]] && break
            continue
        fi
        echo
        echo
        break
    done
done

recorded=$(ls $OUT/*.wav 2>/dev/null | wc -l | tr -d ' ')
echo "──────────────────────────────────────────────────────────────"
echo "Recorded $recorded of $total."
if [[ "$recorded" -eq "$total" ]]; then
    echo "Next:  bench fetch  &&  bench run  &&  bench score   (docs/ASR_BENCHMARK.md)"
fi
