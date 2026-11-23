# Relay keyboard — multi-platform visual and behavior standard (2026-11-23)

## References and confidence

The user requested a PlayStation Portal-style on-screen keyboard, using reference imagery and a common design across Relay ports.

Research references (for design inspection only, **not bundled in released apps**):

1. Sony PlayStation Portal passcode screen (official PlayStation.Blog, 2025-11-05): `https://blog.ja.playstation.com/2025/11/05/20251105-psportal/`. Local reference `docs/references/portal-keyboard/psportal-passcode-reference.png`. It confirms dark/violet UI, clear input focus and controller input idioms, **not** a QWERTY screen.
2. PS5 system text-entry/Store-search screenshot: `https://thebasedotaku.com/guides/play-warzone-on-ps5/`. Local reference `docs/references/portal-keyboard/ps5-search-keyboard-reference.jpg`. It shows a compact dark keyboard, top numeric row, QWERTY alpha rows, visible selected-key border and command row, but is **PS5, not definitive Portal firmware keyboard**.
3. Sony PSTV text entry guide: `https://manuals.playstation.net/document/gb/pstv/basic/osk.html`, documents suggestions, controller confirmation, deletion, case switching and keyboard layout. It is useful for Vita/PSTV behavior, not Portal visual identity.

Use an original Relay implementation, **not** copyrighted screenshots or extracted firmware artwork as UI assets. No verified standalone Portal QWERTY screenshot has been found; do not describe the following as a pixel-exact replica.

## Design contract

Machine-readable layout/palette reference: `assets/relay-keyboard-layout.json`. Its existence does not imply every native renderer consumes it yet.

- Primary surface: translucent ink/charcoal black with deep-violet highlights, subdued dividers and bright readable white text. Distinct focus state is required for D-pad/remote navigation.
- Text input field above key grid, caret and optional suggestions region when the platform supports suggestions.
- First key row digits `1 2 3 4 5 6 7 8 9 0`. Then `QWERTYUIOP`, `ASDFGHJKL`, `ZXCVBNM` with adaptive centering.
- Command row: `a/A` Shift, `@#` symbols, Space, Delete and Done; controller A/Cross selects, B/Circle backs/cancels, arrows/D-pad navigate, touch selects on touchscreen models.
- Optional accessibility: physical keyboard input, voice input with explicit permission where supported, high contrast and localization. Never silently suppress platform-required accessibility text input.
- On a two-screen device, place the keyboard **entirely on the secondary/lower screen**, leaving streamed video and the Relay orb/status on the primary/upper screen.
- On a single-screen device, use a temporary bottom/modal keyboard surface without modifying or capturing other apps.
- The keyboard must never claim to transmit typed text to a PS console unless a real input channel is wired and tested.

## Per-platform implementation matrix

| Platform | Implementation state | Verification remaining |
| --- | --- | --- |
| Android mobile, TV, Google TV, BRAVIA 8 II, Shield, Razer Edge, Retroid, AYN Thor, universal dual-screen, Meta Quest | `PortalOnScreenKeyboard.kt` is shared by all ten Android Gradle flavors; all ten debug APKs compiled. `PortalThorSecondScreen.kt` also renders a separate lower-display keyboard where an independent Android Presentation display exists. | Touch and physical controller tests on target hardware, preserve accessibility text-input fallback; no visual match proof on Android TV/dual-screen devices yet |
| Nintendo 3DS XL and New 3DS XL | Native Citro2D bottom-only QWERTY keyboard, digit row, symbol mode and Shift/Space/Delete/Done. Both old and New 3DS emulator modes render dual-screen UI; New 3DS X opens keyboard and A enters text verified by native Azahar screenshots. | Test remaining touch actions, symbol/shift key cycles and physical hardware; no streamed game/keyboard input transport |
| PS Vita / PSTV | Existing Vita native shell only; update queued | Adapt 960×544 renderer and controller/touch focus; hardware proof |
| Switch | Existing native UI only; update queued | SDL keyboard, touch/Joy-Con/Pro proof |
| Windows, Steam Deck/ROG Ally | Update queued | Native controller-focusable keyboard with OS accessibility fallback |
| iOS/tvOS | Update queued | UIKit/tvOS native focus and text input |
| Xbox Series/Xbox 360 | Update queued | Native keyboard and gamepad focus |
| Roku/Tizen | Update queued | Remote-control keyboard/accessibility feasibility |
| Other proposed PSP/PS3/PS4/PS5 clients | No finished client in inventory | Implement only when a native client actually exists |

**Do not mark platform parity complete merely because this specification exists.** Each build requires rendered screenshot comparison and real input tests.
