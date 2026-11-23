# Credits, source provenance and licensing

Relay is an independently developed user interface and multi-platform application built on existing open-source Remote Play research and libraries. The source tree incorporates upstream components whose licenses, copyright notices and required attributions remain in force. The repository does **not** claim exclusive ownership over those components.

## Remote Play foundations

| Upstream project | Repository | Relationship |
| --- | --- | --- |
| Chiaki | https://github.com/thestr4ng3r/chiaki | Original open-source PlayStation Remote Play client and protocol implementation |
| chiaki-ng | https://github.com/chiaki-ng/chiaki-ng | Maintained fork and base application/library source for this Relay worktree |

See the root `COPYING`, `LICENSES/`, file-level notices and inherited source history for applicable license and attribution details. Files may carry different compatible licenses; do not treat this list as a replacement for their original terms.

The exact initial upstream source commit and the public snapshot's ancestry boundary are recorded in [`docs/UPSTREAM_SOURCE_PROVENANCE.md`](docs/UPSTREAM_SOURCE_PROVENANCE.md).

## Libraries, SDKs and tracked upstream dependencies

| Project | Upstream repository | How Relay uses/references it |
| --- | --- | --- |
| Nanopb | https://github.com/nanopb/nanopb | Protobuf encoding/decoding; linked Git submodule |
| Jerasure | https://github.com/streetpea/jerasure | Forward error correction dependency; linked Git submodule |
| GF-Complete | https://github.com/streetpea/gf-complete | Error correction dependency; linked Git submodule |
| Munit | https://github.com/streetpea/munit | Test support; linked Git submodule |
| Oboe | https://github.com/google/oboe | Android low-latency audio; linked Git submodule |
| Borealis | https://github.com/streetpea/borealis | Console UI dependency in inherited Switch tooling; linked Git submodule |
| curl | https://github.com/curl/curl | Networking dependency; linked Git submodule |
| cpp-steam-tools | https://github.com/streetpea/cpp-steam-tools | Desktop/Steam integration; linked Git submodule |
| devkitPro and libctru | https://github.com/devkitPro/libctru | Nintendo 3DS SDK/runtime interface for the experimental native 3DS source |
| devkitPro libnx | https://github.com/switchbrew/libnx | Nintendo Switch homebrew SDK interface |
| VitaSDK | https://github.com/vitasdk | Native Vita development toolchain/runtime |
| SDL | https://github.com/libsdl-org/SDL | Portable input, audio and rendering API across supported native builds |
| FFmpeg | https://github.com/FFmpeg/FFmpeg | Multimedia decoding/build dependencies where used |
| OpenSSL | https://github.com/openssl/openssl | Encryption/TLS implementation in inherited core and build system |
| nlohmann/json | https://github.com/nlohmann/json | Header-only JSON library used by the experimental console-link source |

## Additional upstream research and optional external integration

The projects below were consulted, cloned for separate research, or used as optional build/integration tools. Their **entire repository copies are excluded from the Relay source commit**. Listing a project here does not imply its code is bundled or that its full feature set works in Relay.

| Project | Original repository | Scope |
| --- | --- | --- |
| PBS Payload | https://github.com/seregonwar/PBS-Payload | Research into experimental console host streaming |
| InstantCaption | https://github.com/zmxhot/InstantCaption | Caption research/integration reference |
| PlayTranslate | https://github.com/dominostars/playtranslate | Multilingual game translation research reference |
| QR Code generator | https://github.com/nayuki/QR-Code-generator | Optional QR algorithm/source reference |
| hacBrewPack | https://github.com/rlaphoenix/hacBrewPack | Switch packaging tool/reference |
| nx-tools | https://github.com/nx-std/tools | Switch tooling reference |
| Playnite FSE Launcher | https://github.com/leeson865/Playnite-FSE-Launcher | PC handheld/launcher research |
| xsdk | https://github.com/xenosworks/xsdk | Xbox 360 homebrew toolchain research |
| JXRlib | https://github.com/4creators/jxrlib | Image format implementation/reference |
| TheosWin | https://github.com/CacheW/TheosWin | Apple platform build research |
| WindowsIPABuilder | https://github.com/iamjrmh/WindowsIPABuilder | iOS packaging research |

For full external-source pin details and reproducible clone setup, see `docs/RELAY_EXTERNAL_SOURCE_MANIFEST.json` and `docs/RELAY_EXTERNAL_CLONES_SETUP.md`. These source links are acknowledgments, **not** permission to redistribute proprietary code, firmware, game assets or private data.

## Visuals and trademarks

The Relay icon, site previews and native interface adaptations are distributed as part of the Relay worktree where rights permit. Sony/Nintendo/Microsoft trademarks are used descriptively for compatibility; Relay is not affiliated with them. Official console screenshots and system UI references do not become part of an open-source graphics license solely because they appear in research material.

## Contributor credit

Actual project contributors are credited through Git history, accepted contributions and the applicable license notices. Public release notes describe technical changes and tested platform support without replacing or obscuring the original authors' required credits.
