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

set -e
cd "$(dirname "$0")/../.."

DEVICE=${AUDIO_DEVICE:-0}
OUT=asr-bench/recordings
mkdir -p $OUT

if ! command -v ffmpeg >/dev/null; then
    echo "ffmpeg not found: brew install ffmpeg" >&2
    exit 1
fi

echo "Microphone: device $DEVICE. List others with:"
echo "  ffmpeg -f avfoundation -list_devices true -i \"\""
echo
echo "Press q to stop each recording. Ctrl-C to quit."
echo

tail -n +2 tools/asr_bench/prompts.tsv | while IFS=$'\t' read -r id lang text; do
    [[ -z "$id" ]] && continue
    if [[ $# -gt 0 ]]; then
        # Only the prompts named on the command line.
        [[ " $* " == *" $id "* ]] || continue
    elif [[ -f "$OUT/$id.wav" ]]; then
        continue
    fi

    echo "──────────────────────────────────────────────────────────────"
    echo "$id  [$lang]"
    echo
    echo "  $text"
    echo
    printf "Enter to record… "
    read -r _ < /dev/tty
    ffmpeg -hide_banner -loglevel error -f avfoundation -i ":$DEVICE" \
        -ar 16000 -ac 1 -y "$OUT/$id.wav" < /dev/tty
    echo "saved $OUT/$id.wav"
    echo
done

echo "Recorded: $(ls $OUT/*.wav 2>/dev/null | wc -l | tr -d ' ') of $(( $(wc -l < tools/asr_bench/prompts.tsv) - 1 ))"
