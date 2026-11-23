# Relay — Nintendo Switch V1 native homebrew port

**Current artifact: `relay-switch1.nro`, built with devkitA64/libnx + SDL2.** This is a functional *native menu/audio renderer* but **not yet a usable PlayStation Remote Play client**. Do not mistake a valid `.nro` or a working menu for complete porting of the Android application's video, pairing, or networking functionality.

## Source and UI/audio preservation

Relay's original Android/Kotlin UI source is the reference: `PortalMainActivity.kt`, `PortalBackdropView.kt`, `PortalMotion.kt`, `PortalMenuAudio.kt`, Android drawable vectors, and `activity_portal_home.xml`. The native C++ Switch renderer copies the original visual geometry and many timing constants, including 2,600 backdrop particles, 19 clusters, seeded Java-compatible random generation, 0.075-radians/second rotation, the intro growth and ring timing, and 300 ms menu transitions. It renders Switch input/navigation with 1920x1080 logical coordinates, scaling to 720p handheld and 1080p docked displays.

Crucially, **the native renderer has NOT been confirmed pixel-identical or animation-frame-identical to Android**: it uses SDL2 drawing in place of Android Canvas/radial shaders. The Switch renderer now uses batched gradient point sprites, circularly clipped animated nebula clouds, a three-stroke intro ring, rounded sheet/cards, matching original audio cue debounce, and corrected touch/D-pad navigation. These fixes have compiled; GPU timing, audiovisual output and all Android screens still require runtime comparison.

The original OGG menu cues and 48 kHz stereo S16 PCM music loop are copied **byte-for-byte** from Android and bundled in Switch RomFS. The Inter variable TTF is also copied unchanged. Relay Android's vector navigation artwork is converted to PNG by `tools/build_assets.py` with `@resvg/resvg-js` using the original path and color data; this is a render-format conversion, not substitute artwork. Audio is played through SDL2_mixer; operation on Switch hardware requires testing.

`ui_audio_animation_lock.json` records SHA-256 checksums for **64 original UI/audio/animation source files**; run `python platforms/switch1/verify_ui_parity.py` to verify no source changes. `romfs/relay/ASSET_PROVENANCE.json` records source hashes for **20 assets** used by the native port. `verify_nro.py` verifies the NRO header and unchanged audio/font file content, but **does not** verify playback or visual fidelity.
The original `docs/branding/relay-icon-original.png` is additionally converted into the NRO's 256×256 JPEG title icon (required format), without editing the PNG. The repositories and UI/animation approaches considered are documented in `UI_ENGINE_RESEARCH.md`.

## Build

From Relay project root on BEN-PC:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\platforms\switch1\build-switch1.ps1
```

This uses installed `C:\devkitPro` and a temporary **V:/W:/X:** drive alias to avoid the spaces in `H:\Samsung Android\PSPortalAndroid`. It regenerates RomFS assets, compares the source hash manifest, invokes devkitA64/libnx `make` and checks the output. Do not run concurrent Gradle native builds that share the project. The resulting `platforms/switch1/relay-switch1.nro` is the **native UI/audio partial port**, not the earlier smoke NRO.

## Backend research and limitations

**No other Remote Play code ports are used.** After the user rejected foreign code ports, the unused Akira research checkout was removed from `platforms/switch1/upstream/`. The current source has Relay's own native UI implementation and an independently written, read-only probe of Relay's own PS5 PBS `/config.json` protocol. A compatible PBS service can be detected without installing payloads or changing PS5 capture settings. The existing NRO still lacks the full video, input and audio streaming engine.

Missing before *complete* release: PSN/PS5 registration, session establishment, video decode/render integration, controller-to-PS5 input, streamed audio and gamepad rumble, JB-specific PBS transport, exact quick-menu/settings views, native translation models, and hardware/playback performance validation. No user keys, pairing secrets or firmware are changed by this target. A+ and L/R menu navigation work in native source, but Connect intentionally displays an explicit backend-unavailable notice rather than pretending streaming starts.
The `relay-switch1-exefs-dev.nsp` remains an **ExeFS development container**, not an installable NSP release. Separately, `nca-title-output/0100abcd23450000.nsp` is a real NCA-based application title container with Program, Control and CNMT content verified by `hactool --verify`. Build it from a verified NRO with `package-nca-title.ps1 -SkipNativeBuild`. Correct NCA packaging does not establish installation, emulator launch, audio output or PS5 streaming; see `NSP_PACKAGING.md`.

## Eden Android emulator test

On November 23, 2026, the Switch NRO was built and copied to the Samsung Galaxy Tab S11 Ultra (Android 16, wireless ADB) at `/sdcard/Download/RelaySwitchTest/Relay-Switch1.nro`. The folder was explicitly granted to the installed **Eden Nightly** emulator. The final built NRO includes the original-derived icon and fully embedded RomFS (~23.76 MB). Eden still logged `Error mmap'ing file` for the granted SAF URI; both `ACTION_VIEW` and `TECH_DISCOVERED` attempts returned `No game found in arguments or intent` and returned to Eden's library. **The NRO did not launch in Eden, so UI, animations and audio could not be visually or audibly verified there.** See `EDEN_TEST.md` and `docs/device-results/eden-relay-test-log.txt` for reproducible evidence.

Eden's upstream homebrew-launch work remained a draft as of November 2026; do not infer NRO incompatibility with physical Switch hardware from this Android emulator failure. No attempt has been made to install the NRO on the user's actual Switch V1.

## November 23 native UI/menu refresh

The subsequent full native Switch application build adds the Android quick
menu's four separate player cards, a scrollable 704-pixel lavender sheet,
Brightness, Audio Devices, Network, Network Status, Settings and Close. Menu
settings invoke real SDL_mixer volume calls; screen brightness uses libnx LBL
when the service grants access. Network information is read through NIFM.
Joy-Con/Pro controller focus, shoulder tab navigation, touch scrolling and
Android-derived L1/R1 hint graphics were added. Orb particle luminance was
tuned against the previous Eden screenshot, and the extra per-frame 16 ms
delay was removed. The original Android renderer and sounds remain unchanged.

The latest NRO is 36,317,184 bytes
(`e1ce13f6cdb136047b3b5d95e73f6015ec8f90f4de7c84392316d01ce778cc12`).
The latest full application NSP is 28,852,440 bytes
(`266fe2d77f9d749e97d4350b2e30e0b82a9af8247d9e91e7ab913d75fff13a12`).
Both passed their packaging/integrity gates; actual new runtime menu visual
and audio testing remains a separate gate. See
`20261123_SWITCH_UI_PARITY_GAP_REPORT.md` for exact changes, button
bindings, supporting evidence, known discrepancies and test status.
