#!/bin/zsh
# Fetch and build everything glide decoding needs from FUTO Swipe: the swipe-library C++ source,
# its ExecuTorch dependency, the JNI library, and the three models.
#
#   tools/fetch_swipe_runtime.sh              arm64-v8a only (the phone)
#   ABIS="arm64-v8a x86_64" tools/…           add the emulator
#
# Lands in two places, both git-ignored:
#   third_party/swipe-library/   sources, jniLibs and the Kotlin API, referenced by build.gradle
#   app/src/main/assets/swipe/   the models, read straight out of the APK at runtime
#
# The app is built to run *without* any of this: when the native library or the models are
# missing, glide typing falls back to our own Kotlin decoder. That is what keeps a clean checkout
# buildable, and it is also the switch the A/B comparison flips.
#
# This deliberately does not use swipe-library's own Makefile, which is Linux-only in ways that
# are not worth patching around: it strips with a linux-x86_64 toolchain path, and its ExecuTorch
# patch step calls GNU `sed -i`, which on BSD sed reads the script as a backup suffix and fails.
# The cmake invocations below are the same ones it makes; the flag list is copied from it and is
# the thing to re-check when the pin moves.

set -e
cd "$(dirname "$0")/.."

# Pinned, for the same reason the ASR runtime is: a decoder that scores differently from the one
# that was measured is a decoder nobody measured.
COMMIT=1b13f2c
REPO=https://gitlab.futo.org/keyboard/swipe-library.git
MODELS_REPO=https://huggingface.co/futo-org/futo-swipe

SRC=third_party/swipe-library
ASSETS=app/src/main/assets/swipe
ABIS=${ABIS:-arm64-v8a}

export ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
NDK=${ANDROID_NDK_HOME:-$(ls -d $ANDROID_HOME/ndk/*/ 2>/dev/null | sort -V | tail -1 | sed 's:/*$::')}
TOOLCHAIN=$NDK/build/cmake/android.toolchain.cmake
[[ -f $TOOLCHAIN ]] || { echo "no NDK toolchain at $TOOLCHAIN" >&2; exit 1; }

JOBS=$(sysctl -n hw.ncpu 2>/dev/null || echo 4)
[[ $JOBS -gt 8 ]] && JOBS=8

# --- sources -------------------------------------------------------------------------------
if [[ ! -f $SRC/CMakeLists.txt ]]; then
    echo "swipe-library @ $COMMIT…"
    mkdir -p third_party
    git clone --recursive --quiet $REPO $SRC
    git -C $SRC checkout --quiet $COMMIT
    git -C $SRC submodule update --init --recursive --quiet
fi

ET=$SRC/third_party/executorch
[[ -f $ET/CMakeLists.txt ]] || { echo "ExecuTorch submodule missing" >&2; exit 1; }

# --- ExecuTorch's codegen needs torchgen, which only ships inside a torch wheel ---------------
VENV=$SRC/.venv
if [[ ! -x $VENV/bin/cmake ]]; then
    echo "python venv with torchgen + cmake…"
    python3 -m venv $VENV
    $VENV/bin/pip install --quiet --upgrade pip cmake pyyaml typing_extensions
    tmp=$(mktemp -d)
    # The CPU index has no macOS wheels; the default one does, and torchgen is pure Python and
    # identical in both.
    $VENV/bin/pip download --quiet --no-deps torch==2.10.0 -d $tmp \
        || $VENV/bin/pip download --quiet --no-deps torch==2.10.0 \
             --index-url https://download.pytorch.org/whl/cpu -d $tmp
    $VENV/bin/python -c "
import zipfile, glob, sysconfig
whl = glob.glob('$tmp/torch-*.whl')[0]
sp = sysconfig.get_path('purelib')
zf = zipfile.ZipFile(whl)
[zf.extract(n, sp) for n in zf.namelist() if n.startswith('torchgen/')]
print('  torchgen from', whl.rsplit('/', 1)[-1])
"
    rm -rf $tmp
fi

# --- our own patch: a dictionary the Kotlin side can own ---------------------------------------
# SwipeEngine takes dictionaries as ITrie pointers and SwipeDecoder.kt passes them as jlongs, but
# nothing in the shipped bindings can produce one -- so the Kotlin API cannot load a dictionary at
# all. Kept as a patch rather than a fork so that moving the pin shows up as a conflict here
# rather than as a silent revert.
if git -C $SRC apply --check ../../tools/patches/swipe-library-trie-jni.patch 2>/dev/null; then
    echo "patching swipe-library: trie JNI"
    git -C $SRC apply ../../tools/patches/swipe-library-trie-jni.patch
fi

# --- the two ExecuTorch fixes swipe-library carries -------------------------------------------
# Applied here rather than through `make patch-et` because that target shells out to GNU sed.
if git -C $ET apply --check ../../patches/executorch-xnnpack-fexceptions.patch 2>/dev/null; then
    echo "patching ExecuTorch: xnnpack -fexceptions"
    git -C $ET apply ../../patches/executorch-xnnpack-fexceptions.patch
fi
python3 - "$ET/third-party/flatcc/include/flatcc/portable/grisu3_print.h" <<'PY'
import sys, pathlib
# The array is one byte short of its own string literal, which -Werror rejects under the NDK.
p = pathlib.Path(sys.argv[1])
s = p.read_text()
old, new = 'static char hexdigits[16] = "0123456789ABCDEF";', 'static char hexdigits[17] = "0123456789ABCDEF";'
if old in s:
    p.write_text(s.replace(old, new))
    print("patching flatcc: hexdigits[17]")
PY

# --- selective op build: only the operators these three models actually use --------------------
OPS=$(cat $SRC/models/*/ops.txt 2>/dev/null | sort -u | grep -v '^$' | paste -sd, -)

ET_FLAGS=(
    -DPYTHON_EXECUTABLE=$PWD/$VENV/bin/python3
    -DEXECUTORCH_BUILD_XNNPACK=ON
    -DEXECUTORCH_BUILD_EXTENSION_MODULE=ON
    -DEXECUTORCH_BUILD_EXTENSION_TENSOR=ON
    -DEXECUTORCH_BUILD_EXTENSION_DATA_LOADER=ON
    -DEXECUTORCH_BUILD_EXTENSION_FLAT_TENSOR=ON
    -DEXECUTORCH_BUILD_EXTENSION_NAMED_DATA_MAP=ON
    -DEXECUTORCH_BUILD_KERNELS_QUANTIZED=OFF
    -DEXECUTORCH_BUILD_VULKAN=OFF -DEXECUTORCH_BUILD_MPS=OFF -DEXECUTORCH_BUILD_COREML=OFF
    -DEXECUTORCH_BUILD_QNN=OFF -DEXECUTORCH_BUILD_SDK=OFF -DEXECUTORCH_BUILD_TESTS=OFF
    -DEXECUTORCH_BUILD_EXAMPLES=OFF -DEXECUTORCH_OPTIMIZE_SIZE=ON
    -DXNNPACK_BUILD_ALL_MICROKERNELS=OFF -DXNNPACK_ENABLE_SPARSE=OFF
    -DXNNPACK_BUILD_TESTS=OFF -DXNNPACK_BUILD_BENCHMARKS=OFF
    -DXNNPACK_ENABLE_ARM_SME=OFF -DXNNPACK_ENABLE_ARM_SME2=OFF
)
[[ -n "$OPS" ]] && ET_FLAGS+=(-DEXECUTORCH_SELECT_OPS_LIST="$OPS")

ANDROID_FLAGS=(
    -DCMAKE_TOOLCHAIN_FILE=$TOOLCHAIN
    -DANDROID_PLATFORM=android-27
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON
)

for abi in ${=ABIS}; do
    if [[ ! -f $ET/cmake-out-$abi/libexecutorch.a ]]; then
        echo "ExecuTorch for $abi (this takes a while)…"
        $VENV/bin/cmake -B $ET/cmake-out-$abi -S $ET -DCMAKE_BUILD_TYPE=Release \
            "${ANDROID_FLAGS[@]}" -DANDROID_ABI=$abi "${ET_FLAGS[@]}" >/dev/null
        $VENV/bin/cmake --build $ET/cmake-out-$abi -j$JOBS >/dev/null
    fi

    if [[ ! -f $SRC/build-android-$abi/libswipe_jni.so ]]; then
        echo "swipe-library for $abi…"
        (cd $SRC && cmake -B build-android-$abi \
            -DCMAKE_TOOLCHAIN_FILE=$TOOLCHAIN -DANDROID_PLATFORM=android-27 \
            -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DANDROID_ABI=$abi \
            -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release . >/dev/null
         cmake --build build-android-$abi -j$JOBS >/dev/null)
    fi

    mkdir -p $SRC/jniLibs/$abi
    cp $SRC/build-android-$abi/libswipe_jni.so $SRC/jniLibs/$abi/
    case $abi in
        arm64-v8a) sysroot=aarch64-linux-android ;;
        x86_64)    sysroot=x86_64-linux-android ;;
    esac
    cxx=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/lib/$sysroot/libc++_shared.so
    [[ -f $cxx ]] && cp $cxx $SRC/jniLibs/$abi/
    echo "  $abi: $(du -h $SRC/jniLibs/$abi/libswipe_jni.so | cut -f1)"
done

# --- the Kotlin binding ------------------------------------------------------------------------
mkdir -p $SRC/kotlin-api/org/futo/ml/inference
cp $SRC/android/src/main/kotlin/org/futo/ml/inference/SwipeDecoder.kt \
   $SRC/kotlin-api/org/futo/ml/inference/

# --- models ------------------------------------------------------------------------------------
if [[ ! -f $ASSETS/encoder/model_fp32.pte ]]; then
    echo "FUTO Swipe models…"
    staging=$(mktemp -d)
    git clone --quiet --depth 1 $MODELS_REPO $staging/futo-swipe
    for pair in honorable_sturgeon:encoder magic_macaw:decoder hungry_jellyfish:contextlm; do
        from=${pair%%:*}
        to=${pair##*:}
        [[ -d $staging/futo-swipe/$from ]] || continue
        mkdir -p $ASSETS/$to
        cp $staging/futo-swipe/$from/* $ASSETS/$to/ 2>/dev/null || true
    done
    rm -rf $staging
    du -sh $ASSETS 2>/dev/null
fi

echo
echo "swipe runtime ready. Rebuild the app and glide decoding will use it:"
echo "  ./gradlew assembleDebug"
