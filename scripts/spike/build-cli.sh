#!/usr/bin/env bash
# Spike tool (never shipped): builds whisper-cli and whisper-bench from the engine submodule for arm64 Android.
# Output: build/spike-arm64 (git-ignored). Needs the NDK and CMake of the README prerequisites.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
ndk="$sdk/ndk/29.0.14206865"
cmake="$sdk/cmake/3.31.6/bin/cmake"
ninja="$sdk/cmake/3.31.6/bin/ninja"
src="$root/whisper/src/main/cpp/whisper.cpp"
out="$root/build/spike-arm64"

[ -f "$ndk/build/cmake/android.toolchain.cmake" ] || { echo "NDK 29.0.14206865 not found under $sdk" >&2; exit 2; }
[ -x "$cmake" ] || { echo "CMake 3.31.6 not found under $sdk" >&2; exit 2; }
[ -f "$src/CMakeLists.txt" ] || { echo "the engine submodule is not checked out: git submodule update --init" >&2; exit 2; }

"$cmake" -S "$src" -B "$out" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" -DCMAKE_MAKE_PROGRAM="$ninja" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=ON -DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON -DGGML_NATIVE=OFF \
  -DGGML_OPENMP=OFF -DGGML_CCACHE=OFF -DWHISPER_BUILD_EXAMPLES=ON -DWHISPER_BUILD_TESTS=OFF \
  -DWHISPER_BUILD_SERVER=OFF
"$cmake" --build "$out" --target whisper-cli whisper-bench -j "$(nproc)"

echo "Built:"
find "$out" -name 'lib*.so' -o -name 'whisper-cli' -o -name 'whisper-bench' | sort
