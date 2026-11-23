# Switch UI, sound and animation port — repo/skill research

The user requires Relay's **existing** PlayStation Portal-style UI, original Relay menu music/cues and original animations; inventing another appearance or replacing the sounds is expressly out of scope. An installed development skill specialized for an exact Relay-to-libnx UI conversion was not available. No third-party GitHub repository contains a drop-in implementation of Relay's proprietary project-specific Android canvas/animation classes. Those are already in this repository and are the only accurate source for the target port.

| Source | URL | Useful capability | Exact Relay UI? | Decision |
| --- | --- | --- | --- | --- |
| **Akira** (chiaki-ng Switch port) | https://github.com/chiaki-ng/akira | Existing Switch PlayStation Remote Play engine, session pairing, decoder and input handling; native Switch hardware APIs | **No** — has a distinct Borealis UI | Research only; local checkout removed after the user explicitly rejected other code ports. No code was integrated |
| **Aurore** | https://github.com/JigSawFr/Aurore | Hardware-accelerated cross-platform XML/animations and CMake Switch build | **No** — general-purpose UI library, not Relay | Evaluated; adapting would still need exact layouts/animations ported from Relay source |
| **Plutonium** | https://github.com/XorTroll/Plutonium | SDL2-based Switch GUI with sound/input capabilities | **No** — library, not Relay | Used SDL2 directly to retain the original artwork and cues |
| **SDL3-nx** | https://github.com/nx-mod/sdl3-nx | Modern deko3d-backed SDL3 GPU rendering/audio/input support | **No** | Evaluated; the devkitPro installation already includes a working SDL2 toolchain |
| **switchapk** | https://github.com/tuckerwales/switchapk | Experimental Android XML/view/animation VM on Switch | **No reliable parity** — Switch audio is not output and native JNI/GL execution on Switch remains experimental | Not used: would make unsupported claims about Relay's Chiaki JNI/video/audio |

## Actual work performed

- Created a native SDL2/libnx Switch `.nro` using unchanged Relay OGG cues, PCM music loop, Inter font and vector nav artwork derived from Android XML with `@resvg/resvg-js`. Original source files remain bit-for-bit untouched.
- Ported a selected subset of `PortalBackdropView.kt` behavior (same particle count, fixed Java RNG seed/sequence, 19 clusters, arc-growth timings, rotation and twinkle constants) to native C++ rendering. Navigation, quick-menu, sounds and 300 ms UI transitions have native implementations.
- Implemented source-hash validation for 64 original UI/audio/animation files and content validation for the Switch RomFS package (20 source assets). Native `relay-switch1.nro` compiled successfully with the installed devkitPro toolchain.
- **Not achieved:** full Android Canvas Shader fidelity, native PlayStation remote play integration, pixel-by-pixel animation comparisons and Switch/Eden verified output. None is falsely marked done.

## Eden test constraints

The final NRO can be staged to the Samsung Galaxy Tab S11 Ultra, but this installed Eden Nightly build currently fails to map the SAF content URI even after being granted the `RelaySwitchTest` directory. It returns to the library before starting Relay. Upstream [Eden draft PR #4693](https://git.eden-emu.dev/eden-emu/eden/pulls/4693) notes that homebrew-menu launching on Android still freezes as of November 2026. Therefore exact visual/sound/animation verification must not be claimed from Eden at this stage.

See `EDEN_TEST.md`, `README.md`, `build-switch1.ps1` and the unchanged Android source manifest.
