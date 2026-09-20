#!/usr/bin/env bash
# Stage the payload into the :modelpack module.
#
#   tools/pack_models.sh [--check]
#
# Copies the two things that never change out of the fetched runtimes and into the pack module's
# own source tree:
#
#   app/src/main/assets/asr/sensevoice/model.int8.onnx   the 239 MB model
#   app/src/main/assets/asr/silero_vad.onnx              the VAD
#   app/src/main/assets/swipe/                           ExecuTorch .pte models + vocab
#   third_party/*/jniLibs/arm64-v8a/*.so                 the native runtimes
#
# `--check` reports what would be copied and changes nothing, for a preflight.
#
# Why a copy and not a `srcDir` symlink or override: see modelpack/build.gradle.kts. A
# `sourceSets` override is applied after the asset list is computed, so the files sit on disk and
# never reach the APK -- a pack that installs in a second and contains nothing. Copying is dull,
# leaves the pack module self-describing, and can be checked by looking at the APK.
#
# The copies are hard links when the filesystem allows it, which on the same volume means the pack
# costs no extra disk: `cp -l` falls back to a copy across filesystems, and the ~320 MB is the
# same either way for a fresh fetch.

set -euo pipefail
cd "$(dirname "$0")/.."
REPO=$PWD

CHECK=0
[[ "${1:-}" == "--check" ]] && CHECK=1

PACK=$REPO/modelpack/src/main
ASSETS=$REPO/app/src/main/assets
ABI=arm64-v8a

# The model pack is only ever installed on the phone, never the emulator, so it carries one ABI.
# That is the whole reason for the split: x86_64 native libraries are 40 MB of bytes the device
# can never load.
copy() {
    local src=$1 dst=$2
    [[ -f "$src" ]] || { echo "  MISSING $src" >&2; return 1; }
    if [[ $CHECK -eq 1 ]]; then
        printf '  would stage %-58s %8s\n' "${src#$REPO/}" "$(du -h "$src" | cut -f1)"
        return 0
    fi
    mkdir -p "$(dirname "$dst")"
    # Already identical (same inode after a previous hard link, or the same bytes): leave it.
    if [[ -f "$dst" ]] && cmp -s "$src" "$dst"; then
        return 0
    fi
    rm -f "$dst"
    cp -l "$src" "$dst" 2>/dev/null || cp "$src" "$dst"
}

echo "staging the model pack payload ($ABI)…"

copy "$ASSETS/asr/sensevoice/model.int8.onnx" "$PACK/assets/asr/sensevoice/model.int8.onnx"
copy "$ASSETS/asr/sensevoice/tokens.txt"      "$PACK/assets/asr/sensevoice/tokens.txt"
copy "$ASSETS/asr/silero_vad.onnx"            "$PACK/assets/asr/silero_vad.onnx"

# The .pte models are a directory rather than a list: the encoder, the decoder and the context LM
# are added and removed by the swipe fetch script, and a hand-maintained list here would drift
# from it and quietly ship a pack with the fallback decoder only.
if [[ -d "$ASSETS/swipe" ]]; then
    while IFS= read -r -d '' f; do
        copy "$f" "$PACK/assets/swipe/${f#$ASSETS/swipe/}"
    done < <(find "$ASSETS/swipe" -type f -print0)
else
    echo "  MISSING $ASSETS/swipe (run tools/fetch_swipe_runtime.sh)" >&2
    exit 1
fi

for lib in "$REPO"/third_party/sherpa-onnx/jniLibs/$ABI/*.so \
           "$REPO"/third_party/swipe-library/jniLibs/$ABI/*.so
do
    [[ -e "$lib" ]] || continue
    copy "$lib" "$PACK/jniLibs/$ABI/$(basename "$lib")"
done

if [[ $CHECK -eq 1 ]]; then
    echo "--check: nothing written"
    exit 0
fi

echo
echo "pack payload: $(du -sh "$PACK/assets" "$PACK/jniLibs" 2>/dev/null | awk '{printf "%s %s  ", $1, $2}')"
echo "assemble with: ./gradlew :modelpack:assembleDebug"
