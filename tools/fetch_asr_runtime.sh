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

# Only what an offline recogniser, a VAD and the keyword spotter touch. Pulling the whole
# kotlin-api directory would drag in TTS and diarization, which this keyboard has no use for.
#
# Keyword spotting *is* used, contrary to what this list said before: spoken punctuation is a
# command vocabulary, and a general recogniser ranks "comma" against "come" and "calm" on a text
# prior where the punctuation sense is rare. The spotter asks a different question -- did this
# phoneme sequence fire above a threshold -- which does not degrade as the word is reduced.
KOTLIN_FILES=(
    OfflineRecognizer.kt
    OfflineStream.kt
    FeatureConfig.kt
    HomophoneReplacerConfig.kt
    QnnConfig.kt
    Vad.kt
    KeywordSpotter.kt
    OnlineStream.kt
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

# OnlineModelConfig.kt is a *trim* of upstream's OnlineRecognizer.kt rather than a copy of a
# file that exists under that name. KeywordSpotter is a streaming transducer and needs the
# Online* config classes, but upstream ships them in one 799-line file with a streaming
# recogniser and a large lookup table this keyboard never calls. Taking the head keeps the JNI
# field layout identical while leaving the rest out.
if [[ ! -f $RUNTIME/kotlin-api/OnlineModelConfig.kt ]]; then
    echo "sherpa-onnx kotlin-api/OnlineModelConfig.kt (trimmed from OnlineRecognizer.kt)"
    tmp=$(mktemp)
    curl -fsSL -o "$tmp" \
        "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v$VERSION/sherpa-onnx/kotlin-api/OnlineRecognizer.kt"
    {
        echo "// Upstream sherpa-onnx v$VERSION, sherpa-onnx/kotlin-api/OnlineRecognizer.kt, lines 1-54."
        echo "//"
        echo "// Only the configuration data classes are kept: KeywordSpotter needs OnlineModelConfig (it is a"
        echo "// streaming transducer), but nothing here uses the streaming *recogniser*, and upstream ships"
        echo "// both in one 799-line file whose remainder is a large getModelConfig() lookup table."
        echo "//"
        echo "// These declarations are read field-by-field by the JNI layer, so their names, order and types"
        echo "// must match upstream exactly. Re-copy rather than edit when bumping the pinned version."
        echo "//"
        echo "// The range ends at 54, not 53: line 54 is OnlineModelConfig's closing paren."
        echo "package com.k2fsa.sherpa.onnx"
        # From line 4, skipping upstream's own package line and the AssetManager import that
        # only the recogniser needs.
        sed -n '4,54p' "$tmp"
    } > $RUNTIME/kotlin-api/OnlineModelConfig.kt
    rm -f "$tmp"
fi

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

# --- keyword spotter ------------------------------------------------------------------------
# A 3M-parameter streaming zipformer, int8, about 5 MB next to SenseVoice's 239 MB. It listens
# only for the spoken punctuation words; SenseVoice still writes the text.
#
# Both scripts matter. The model is zh+en, and its English keywords are ARPAbet phoneme strings
# while its Chinese ones are pinyin with tone marks -- see asr/punctuation_keywords.txt, which
# tools/build_punctuation_keywords.py generates from the en.phone dictionary shipped inside this
# same tarball. Only the chunk-16 int8 files are kept; chunk-8 trades latency for accuracy in a
# direction that does not matter when the spotter is already running behind a VAD.
KWS_MODEL=sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20
if [[ ! -f $ASSETS/kws/encoder.onnx ]]; then
    echo "keyword spotter ($KWS_MODEL)…"
    mkdir -p $ASSETS/kws
    tarball=$(mktemp -t kws).tar.bz2
    curl -fsSL -o "$tarball" \
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/$KWS_MODEL.tar.bz2"
    staging=$(mktemp -d)
    tar xf "$tarball" -C "$staging"
    src=$staging/$KWS_MODEL
    cp $src/encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx $ASSETS/kws/encoder.onnx
    cp $src/decoder-epoch-13-avg-2-chunk-16-left-64.onnx      $ASSETS/kws/decoder.onnx
    cp $src/joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx  $ASSETS/kws/joiner.onnx
    cp $src/tokens.txt                                        $ASSETS/kws/tokens.txt
    # The pronunciation dictionary is build-time input, not a shipped asset: the keywords file
    # it produces is committed, so the 3.2 MB dictionary stays out of the APK.
    mkdir -p tools/asr_bench
    cp $src/en.phone tools/asr_bench/en.phone
    rm -rf "$tarball" "$staging"
    echo "  $(du -sh $ASSETS/kws | cut -f1)"
fi

echo
echo "runtime: $(du -sh $RUNTIME | cut -f1)   assets: $(du -sh $ASSETS | cut -f1)"
