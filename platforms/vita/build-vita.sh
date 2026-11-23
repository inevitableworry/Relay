#!/usr/bin/env bash
set -euo pipefail
SOURCE='/mnt/h/Samsung Android/PSPortalAndroid/platforms/vita'
# VitaSDK's vita-pack-vpk tool does not safely handle source asset paths with
# spaces. Stage the exact port sources under a space-free WSL path before CMake.
# Runs under either the user's normal WSL account or root. No administrator
# prompt is required merely to stage files and build the Vita package.
ROOT="${HOME}/relay-vita-source"
export VITASDK=/opt/vitasdk
export PATH="$VITASDK/bin:$PATH"
BUILD="${HOME}/relay-vita-stage-build"
mkdir -p "$SOURCE/dist"
# Generated packages only; remove stale development VPKs before new packaging.
rm -f "$SOURCE/dist/Relay-PSVita.vpk" "$SOURCE/dist/Relay-PSVitaTV-ARMv7-VitaSDK-dev.vpk" "$SOURCE/dist/Relay-PSVita-Handheld-ARMv7-VitaSDK-dev.vpk" "$SOURCE/dist/Relay-PSVita-PSTV-ARMv7-VitaSDK-dev.vpk"
mkdir -p "$ROOT"
cp "$SOURCE/CMakeLists.txt" "$ROOT/CMakeLists.txt"
cp -a "$SOURCE/source" "$SOURCE/generated" "$SOURCE/assets" "$ROOT/"
cmake -S "$ROOT" -B "$BUILD" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$VITASDK/share/vita.toolchain.cmake" \
    -DCMAKE_BUILD_TYPE=Release
cmake --build "$BUILD"
mkdir -p "$SOURCE/dist"
cp "$BUILD/relay_vita.vpk" "$SOURCE/dist/Relay-PSVita-PSTV-ARMv7-VitaSDK-dev.vpk"
sha256sum "$SOURCE/dist/Relay-PSVita-PSTV-ARMv7-VitaSDK-dev.vpk"
echo "VITA_VPK=$SOURCE/dist/Relay-PSVita-PSTV-ARMv7-VitaSDK-dev.vpk"
