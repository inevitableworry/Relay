#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
SDK="${PS5_PAYLOAD_SDK:-$HOME/axm-homebrew/.deps/ps5-payload-sdk}"
if [[ ! -f "$SDK/toolchain/prospero.cmake" ]]; then
  echo "Missing preinstalled PS5 payload SDK: $SDK" >&2
  exit 2
fi
export PS5_PAYLOAD_SDK="$SDK"
source "$SDK/toolchain/prospero.sh"
stage="${RELAY_PS5_LINK_STAGE:-$HOME/relay-social-link-stage}"
build="${RELAY_PS5_LINK_BUILD:-$HOME/relay-social-link-build}"
mkdir -p "$stage" "$build" "$root/dist"
tar -C "$root" --exclude=./dist --exclude=./vendor/qrcodegen-upstream \
  -cf - CMakeLists.txt source vendor/qrcodegen vendor/nlohmann \
  | tar -C "$stage" -xf -
cmake -S "$stage" -B "$build" \
  -DCMAKE_TOOLCHAIN_FILE="$SDK/toolchain/prospero.cmake" \
  -DRELAY_PS5=ON -DCMAKE_BUILD_TYPE=Release
cmake --build "$build" --target relay-social-link -- -j4
cp "$build/relay-social-link.elf" "$root/dist/Relay-SocialLink-PS5-0.1.elf"
ls -lh "$root/dist/Relay-SocialLink-PS5-0.1.elf"
