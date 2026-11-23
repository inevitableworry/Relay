# Relay for original 3DS XL and New 3DS XL — experimental

One ARM11 binary targets both models (`.3dsx` homebrew format); hardware detection flags New 3DS capability, with optional later CPU/L2 optimizations. This is a first native Relay-styled dual-screen UI prototype, not a streaming client. Top screen: 400×240 UI with animated orb approximation; bottom: 320×240 independent touch/D-pad menu. A/B/X/Start and touch navigation are present. The old 3DS's 128 MB RAM/268 MHz ARM11-class CPU, shared graphics resources, and lack of modern hardware H.264 playback make the entire Android Remote Play stack infeasible as a direct port; New 3DS offers extra CPU/RAM but still needs research and real decoding benchmarks.

## Current dual-screen interface

- **Top (400×240):** preserves the animated Relay orb, system identity and console/stream status. Menus and keyboard do not cover this display. The stream area currently shows an accurate *No active stream* status; video decoding has not been implemented.
- **Bottom (320×240 touchscreen):** contains Remote Play, Cloud, Search and Settings navigation. Selecting Search opens a dedicated native touch keyboard with Shift, Space, Delete, Done, and a local 31-character search buffer. Entered text appears on the upper Search page and lower edit field.
- **Controls:** D-pad navigates menu/keyboard; A selects or types; B returns/closes; X opens/closes keyboard; Y backspaces in keyboard; START exits. Touch works in the bottom-screen menu and keyboard. The app does not pretend these controls are already delivered to a remote PS5.
- One shared ARM11 build supports original and New 3DS XL, and detects New 3DS hardware at runtime. No special New 3DS-only CPU mode is required.

**Preferred format: `.3dsx` only.** Build with `powershell -ExecutionPolicy Bypass -File .\platforms\3ds\build-3ds.ps1`. The project uses devkitARM/libctru/citro2d. Older experimental CIA and CCI files may remain in the archive but are no longer generated. A compiled binary is not evidence of on-device usability.

**Azahar 2126.2 test:** The emulator recognized the decrypted CCI window as `Azahar 2126.2 | Relay`. Headless Win32 `PrintWindow` screenshots captured only a black GPU content surface: the actual UI cannot be visually verified from these captures. The `.3dsx` test stayed on `Now Loading` and the `.cia` CLI argument opened the application's library screen, rather than validating installed CIA launch. The old/New hardware profiles and functioning framebuffer, controller and audio still need actual emulator or hardware inspection. Screenshots and log findings are under `docs/screenshots/` and `docs/RELAY_3DS_AZAHAR_TEST.md`.

**Latest validated UI build:** Native Azahar 2126.2 screenshots show both screens rendered on original and New 3DS profiles. New 3DS mapped X opens the keyboard and mapped A types `1`, visible in both lower and upper search fields. Files: `docs/screenshots/relay-3ds-azahar-keyboard-X-test-20261123.png`, `docs/screenshots/relay-3ds-azahar-keyboard-A-typed-20261123.png`, `docs/screenshots/relay-3ds-azahar-old3ds-native-20261123.png`. The standalone 3DSX can log a missing program ID without preventing UI rendering. Full Remote Play, touch typing and physical device compatibility remain unverified. See `docs/RELAY_KEYBOARD_TEST_RESULTS_20261123.md`.

Azahar hardware emulation must validate both old and New 3DS modes, top and bottom screen rendered frames, animation and input. No emulator screenshot or gameplay playback should be claimed until actually captured.
