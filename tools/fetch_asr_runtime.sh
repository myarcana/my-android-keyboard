#!/bin/zsh
# Fetch everything dictation needs that is too large to commit: the sherpa-onnx Android runtime,
# its Kotlin bindings, the Silero VAD, and the SenseVoice model itself.
#
#   tools/fetch_asr_runtime.sh
#
# Lands in two places, both git-ignored:
#   third_party/sherpa-onnx/   native libraries and Kotlin API, referenced by app/build.gradle.kts
#   app/src/main/assets/asr/   the models, read straight out of the APK at runtime
#
# The version is pinned to the one the benchmark scored. docs/ASR_BENCHMARK.md chose SenseVoice
# on numbers produced by sherpa-onnx 1.13.6, so shipping a different runtime would mean shipping
# something that was never measured -- and this project has already been bitten once by a
# sherpa-onnx version behaving differently from another implementation of the same model.

set -e
cd "$(dirname "$0")/.."

VERSION=1.13.6
RUNTIME=third_party/sherpa-onnx
ASSETS=app/src/main/assets/asr
ABIS=(arm64-v8a x86_64)

# Only what an offline recogniser and a VAD touch. Pulling the whole kotlin-api directory would
# drag in TTS, diarization and keyword spotting, none of which this keyboard has any use for.
KOTLIN_FILES=(
    OfflineRecognizer.kt
    OfflineStream.kt
    FeatureConfig.kt
    HomophoneReplacerConfig.kt
    QnnConfig.kt
    Vad.kt
)

mkdir -p $RUNTIME/kotlin-api $RUNTIME/jniLibs $ASSETS/sensevoice

# --- native runtime -----------------------------------------------------------------------
if [[ ! -f $RUNTIME/jniLibs/arm64-v8a/libsherpa-onnx-jni.so ]]; then
    echo "sherpa-onnx $VERSION native runtime…"
    tarball=$(mktemp -t sherpa).tar.bz2
    curl -fsSL -o "$tarball" \
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VERSION/sherpa-onnx-v$VERSION-android.tar.bz2"
    staging=$(mktemp -d)
    tar xjf "$tarball" -C "$staging"
    for abi in $ABIS; do
        mkdir -p $RUNTIME/jniLibs/$abi
        cp "$staging"/jniLibs/$abi/*.so $RUNTIME/jniLibs/$abi/
    done
    rm -rf "$tarball" "$staging"
    echo "  $(du -sh $RUNTIME/jniLibs | cut -f1) in ${#ABIS} ABIs"
fi

# --- kotlin bindings ----------------------------------------------------------------------
for f in $KOTLIN_FILES; do
    [[ -f $RUNTIME/kotlin-api/$f ]] && continue
    echo "sherpa-onnx kotlin-api/$f"
    curl -fsSL -o $RUNTIME/kotlin-api/$f \
        "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v$VERSION/sherpa-onnx/kotlin-api/$f"
done

# --- models -------------------------------------------------------------------------------
if [[ ! -f $ASSETS/silero_vad.onnx ]]; then
    echo "Silero VAD…"
    curl -fsSL -o $ASSETS/silero_vad.onnx \
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
fi

# Reuse the benchmark's copy when it is there -- it is the same file, and it is 239 MB.
SENSEVOICE_REPO=csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17
for f in model.int8.onnx tokens.txt; do
    [[ -f $ASSETS/sensevoice/$f ]] && continue
    if [[ -f models/asr/sensevoice/$f ]]; then
        echo "SenseVoice $f (from models/asr/)"
        cp models/asr/sensevoice/$f $ASSETS/sensevoice/$f
    else
        echo "SenseVoice $f…"
        curl -fsSL -o $ASSETS/sensevoice/$f \
            "https://huggingface.co/$SENSEVOICE_REPO/resolve/main/$f"
    fi
done

echo
echo "runtime: $(du -sh $RUNTIME | cut -f1)   assets: $(du -sh $ASSETS | cut -f1)"
