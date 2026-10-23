#!/usr/bin/env bash
# Builds Cosmos Double Doors and packages dist/cosmos-double-doors.levipack.
#
#   scripts/build.sh                 # uses $ANDROID_NDK_HOME / $ANDROID_NDK_ROOT
#   NDK=/path/to/ndk scripts/build.sh
#   SKIP_DEVICE_TESTS=1 scripts/build.sh
#
# Requires: cmake >= 3.22, ninja, a host C++20 compiler, Android NDK (r26+),
# python3, zip. qemu-aarch64(-static) is used for device tests when present.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK="${NDK:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}"
BUILD_TYPE="${BUILD_TYPE:-Release}"
MOD_ID="cosmos-double-doors"
LIB="libcosmos_double_doors.so"
EXTRA_CMAKE_ARGS=()
if [[ -n "${PRELOADER_ANDROID_ROOT:-}" ]]; then
  EXTRA_CMAKE_ARGS+=("-DPRELOADER_ANDROID_ROOT=${PRELOADER_ANDROID_ROOT}")
fi

if [[ -z "$NDK" || ! -f "$NDK/build/cmake/android.toolchain.cmake" ]]; then
  echo "Android NDK not found; set NDK or ANDROID_NDK_HOME" >&2
  exit 1
fi

HOST_BUILD="$ROOT/build/host"
ANDROID_BUILD="$ROOT/build/android-arm64-v8a"
DIST="$ROOT/dist"
PKG="$DIST/$MOD_ID"

echo "==> Host build (config generator + logic tests)"
cmake -S "$ROOT" -B "$HOST_BUILD" -G Ninja -DCMAKE_BUILD_TYPE="$BUILD_TYPE" \
  "${EXTRA_CMAKE_ARGS[@]}"
cmake --build "$HOST_BUILD"
ctest --test-dir "$HOST_BUILD" --output-on-failure

echo "==> Android arm64-v8a build"
cmake -S "$ROOT" -B "$ANDROID_BUILD" -G Ninja \
  -DCMAKE_BUILD_TYPE="$BUILD_TYPE" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 \
  -DANDROID_STL=c++_shared \
  "${EXTRA_CMAKE_ARGS[@]}"
cmake --build "$ANDROID_BUILD" --target cosmos_double_doors

if [[ "${SKIP_DEVICE_TESTS:-0}" != "1" ]]; then
  QEMU="$(command -v qemu-aarch64-static || command -v qemu-aarch64 || true)"
  if [[ -n "$QEMU" ]]; then
    echo "==> Device tests under $QEMU"
    cmake --build "$ANDROID_BUILD" --target cdd_device_tests
    "$QEMU" "$ANDROID_BUILD/out/arm64-v8a/cdd_device_tests"
  else
    echo "==> qemu-aarch64 not found; skipping device tests"
  fi
fi

echo "==> Packaging"
rm -rf "$DIST"
mkdir -p "$PKG"
cp "$ROOT/manifest.json" "$PKG/manifest.json"
"$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip --strip-unneeded \
  -o "$PKG/$LIB" "$ANDROID_BUILD/out/arm64-v8a/$LIB"
"$HOST_BUILD/cdd_config_gen" "$PKG"
cp "$ROOT/docs/PACKAGE_README.txt" "$PKG/README.txt"

# Unstripped copy for symbolising crash reports.
mkdir -p "$DIST/symbols"
cp "$ANDROID_BUILD/out/arm64-v8a/$LIB" "$DIST/symbols/$LIB"

(cd "$PKG" && zip -X -r -q "$DIST/$MOD_ID.levipack" .)

python3 "$ROOT/scripts/validate_levipack.py" "$DIST/$MOD_ID.levipack"
echo "==> Done: $DIST/$MOD_ID.levipack"
