# Relay Switch 1 native UI parity — 2026-11-23

## Reference and scope

Android is authoritative: `PortalMainActivity.kt`, `PortalQuickMenu.kt`,
`PortalBackdropView.kt`, `PortalUi.kt`, `PortalMenuAudio.kt`, and the
real Android home and quick-menu captures under `docs/screenshots/`.
The source file changed for this pass was
`platforms/switch1/source/main.cpp`; Vita code is untouched.

The prime coordinator captured
`docs/screenshots/android-vs-switch-baseline-20261123.png` before this change.
That comparison shows a target orb with brighter, denser point detail and
insufficient cloud depth, a larger nav/control presentation, and a different
title placement versus the Android reference. The measured 19.3 FPS in the
earlier emulator screenshot is **baseline evidence only**; it does not
describe this new binary's speed.

## Implemented in the new native build

| Area | Change |
|---|---|
| Home navigation | Preserves original converted Android nav art; adds original L1/R1 hint textures, optional hint visibility, and tab focus sounds. L/R and D-pad change tabs. |
| Orb/material | Maintains Android's seeded 2,600 particles, 19 clouds, source-derived gradients, easing and rotation. Reduces the SDL particle overexposure observed in the baseline comparison and allows motion reduction. |
| Quick menu | Sliding 704-reference-pixel sheet with dimmed background and translucent lavender cards, rounded focus outlines, vertical scrolling and independent focus for all rows. Adds Remote Play JB, 4 player tiles, Brightness, Audio Devices, Network, Network Status, Settings and Close. |
| Players | Renders four individual controller/touch-focusable waiting-state tiles. Opens player-specific page explaining that the PS5 assigns player accounts; Connect performs the existing PS5 service readiness check. No preassigned accounts invented. |
| Brightness | Reads current Switch brightness through libnx LBL when available; Joy-Con/Pro Left/Right and touch slider adjust the actual LCD brightness using LBL service calls. If unavailable, previous setting is preserved; no change is falsely reported. |
| Audio Devices | Exposes real menu cue volume and menu-loop volume, with mute/unmute controls that call SDL_mixer. Menu cues remain the original Android OGG files; no synthetic cues were added. |
| Network / Network Status | Reads local IP/connection state via libnx NIFM and refreshes the existing PBS service probe on demand. Displayed PBS target remains the current prototype endpoint, 192.0.2.10:9999. |
| Settings | Interactive Reduce Motion, Controller Hints, Menu Music, Menu Sounds, and Back actions with focus/audio feedback. |
| Gamepad & touch | D-pad Up/Down reaches every quick-menu item, Left/Right adjusts sliders, A confirms, B goes back, X opens/closes menu. Plus remains native exit and Minus opens Settings-focused quick menu. Touch supports tiles, taps and vertical scrolling. |
| CPU/frame pacing | Removes the extra `SDL_Delay(16)` after SDL present/VSync, a suspected avoidable cap. FPS must be captured again under the same device conditions. |

## Verified build/asset gates

- `python tools/check_relay_ui_parity.py`: **PASS**, zero missing/mismatched canonical resources.
- `python platforms/switch1/verify_ui_parity.py`: **PASS**, 64/64 Android source hashes preserved.
- `build-switch1.ps1 -SkipAssetRefresh`: **PASS**. Native ELF/NRO and RomFS generated.
- `verify_nro.py`: **PASS**, original asset provenance verified for 20 files.
- `package-nca-title.ps1 -SkipNativeBuild`: **PASS**. All Program and Control NCA checks and both content-meta records verified by `hactool`.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `platforms/switch1/relay-switch1.nro` | 36,317,184 | `e1ce13f6cdb136047b3b5d95e73f6015ec8f90f4de7c84392316d01ce778cc12` |
| `platforms/switch1/nca-title-output/0100abcd23450000.nsp` | 28,852,440 | `266fe2d77f9d749e97d4350b2e30e0b82a9af8247d9e91e7ab913d75fff13a12` |

Previous known-good archived NSPs remain intact. This NSP is the full NCA-based
application title with Program, Control and CNMT, not the ExeFS development
container. It still needs a successful actual emulator/device install and launch.

## Unverified or still missing (do not count as passes)

- **Updated runtime screenshot:** latest build has not yet been compared against
  Android at equivalent home, quick-menu, settings and cloud/search states at
  0.5, 1, 3, 10 and 30 seconds. The real Eden screenshot baseline predates
  this pass. In-flight Samsung Eden test is owned by the prime coordinator.
- **Actual FPS and visual quality:** retest FPS with the new NRO/NSP. The orb
  still has an approximate SDL raster pipeline rather than identical Android
  Canvas anti-aliasing and shader color management. Geometry and text must be
  re-tuned against same-state captures. Current OLED/handheld pixel fidelity
  and all menu page layouts need captured evidence.
- **Input:** source routes Joy-Con, Pro Controller, native touch and keyboard
  test keys, but detached Joy-Con, sideways Joy-Con, all menu focus states,
  brightness service permissions and streaming input have not been exercised
  end to end on a physical Switch V1.
- **Sound:** SDL_mixer opens the original OGG cues and PCM music loop and
  directly controls volumes. Actual audible playback, channel isolation,
  correct event timing and HDMI/handheld audio routing are **not verified**.
  PS5 decoded stream audio is a separate unverified backend gate.
- **Quick menu feature parity:** This pass adds real settings/audio/network
  handling, but does not port every Android Settings screen, network
  configuration editor, host-account selection, or full four-player session
  identity. The native status page intentionally displays honest probe results.
- **Remote Play pipeline:** the existing experimental PS5 PBS video/audio/
  controller bridge is not demonstrated as stable full Remote Play; PSN login,
  pairing, connection/recovery UX and translation features remain incomplete.

## Controller instructions for emulator/hardware QA

From home, press X to open quick menu. Press Down repeatedly through Remote
Play JB, P1-P4, Brightness, Audio Devices, Network, Network Status, Settings
and Close; confirm every visible focus outline and focus cue. Use Left/Right
on Brightness, and on the two Audio Devices sliders, to verify that the native
values and actual hardware/audio output change. Use B to return from a nested
page to the main quick menu and a second B to close. Verify touch selection and
vertical scroll in handheld orientation. L/R navigate home tabs; Minus opens
the quick sheet focused on Settings. Compare source visual captures to the
Android home and quick-menu reference at matching display aspect ratios.

Once runtime screenshots and audio tests exist, record their paths, frame
timings, current FPS, expected versus actual menus, test hardware/firmware and
any concrete crash/errors here and in `RELAY_BUILD_PARITY_MASTER.md`.
