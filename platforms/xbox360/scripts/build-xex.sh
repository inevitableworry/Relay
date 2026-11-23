#!/usr/bin/env bash
# Independent Relay Xbox 360 native PowerPC build; run inside WSL2 Ubuntu.
# No Microsoft proprietary console SDK or other Remote Play implementation.
set -euo pipefail

ROOT='/mnt/h/Samsung Android/PSPortalAndroid/platforms/xbox360'
XSDK='/root/xsdk'
TOOLCHAIN='/root/xsdk-gcc'
WORK='/root/relay360-build'

if [[ ! -x "$TOOLCHAIN/bin/powerpc-xsdk-elf-gcc" ]]; then
  echo 'MISSING_XSDK_CROSS_COMPILER: build the xenosworks/xsdk PowerPC toolchain first' >&2
  exit 3
fi
if [[ ! -f "$XSDK/Makefile" ]]; then
  echo 'MISSING_XSDK_LIBRARY_SOURCE' >&2
  exit 4
fi
if [[ ! -f "$ROOT/generated/relay_particles.h" ]]; then
  echo 'MISSING_GENERATED_RELAY_PARTICLE_DATA' >&2
  exit 5
fi

mkdir -p "$WORK/source" "$WORK/generated" "$ROOT/dist"
cp "$ROOT/CMakeLists.txt" "$WORK/CMakeLists.txt"
cp "$ROOT/source/main.c" "$WORK/source/main.c"
cp "$ROOT/generated/relay_particles.h" "$WORK/generated/relay_particles.h"

make -C "$XSDK" -j12 build/libxsdk.a \
  TOOLCHAIN="$TOOLCHAIN/bin/powerpc-xsdk-elf-"

cmake -S "$WORK" -B "$WORK/build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$XSDK/cmake/xsdk.cmake" \
  -DXSDK_TOOLCHAIN="$TOOLCHAIN" -DCMAKE_BUILD_TYPE=Release
cmake --build "$WORK/build"

if [[ ! -f "$WORK/build/relay360.xex" ]]; then
  echo 'NATIVE_RELAY_XEX_MISSING' >&2
  exit 6
fi
cp "$WORK/build/relay360.xex" "$ROOT/dist/relay360.xex"
sha256sum "$ROOT/dist/relay360.xex"
echo "RELAY_XBOX360_NATIVE_BUILD=$ROOT/dist/relay360.xex"
