# Relay / PS Portal Android — master to-do

## 2026-11-23 — New target and build policy

- [x] Research PlayStation Portal text-entry visual conventions and PS5 keyboard reference, save internal screenshot references and define `docs/RELAY_KEYBOARD_STANDARD.md` + `assets/relay-keyboard-layout.json` (no unsupported pixel-exact claim).
- [x] Implement shared Android keyboard on the Search field, with digit row, QWERTY, symbol mode, Shift, Space, Delete and Done, and a dedicated lower-display version in dual-screen Android.
- [x] Build all ten Android device flavors with the shared keyboard successfully; hashes in `docs/RELAY_KEYBOARD_TEST_RESULTS_20261123.md`.
- [x] Render the bottom-only PS-style keyboard in Azahar New 3DS mode and verify mapped X opens keyboard, mapped A inserts `1` into both upper and lower text fields via real native emulator screenshots.
- [x] Verify the same 3DSX boots and renders top/bottom Relay screens under Azahar's original 3DS hardware profile; restore original Azahar configuration identically after testing.
- [ ] Complete touch, Shift, symbols, Space, Delete, Done, physical gamepad focus tests on 3DS hardware and Azahar; keyboard text is local only, no remote PS console input transport yet.
- [ ] Port shared keyboard layout and interaction model to remaining native Relay ports (Vita/PSTV, Switch, Windows, Steam Deck, Xbox, Apple, Roku, Tizen); document real runtime results separately.
- [ ] Test Android custom keyboard on each real device, including secondary-display positioning and accessibility/IME fallback.

- [x] Universalize AYN Thor-style secondary-display behavior into `dualScreen` ARMv7/ARM64 Android flavor, standard `DisplayManager` capability detection; preserve legacy `aynThor` flavor.
- [x] Compile universal ARMv7+ARM64 Android dual-screen APK, SHA-256 recorded in master.
- [ ] Test universal Android dual-screen APK on different real dual-screen devices; verify presentation setup and live streamed video/audio/controller.
- [x] Create devkitARM/libctru/citro2d experimental single `.3dsx` for old 3DS XL and New 3DS XL with 400×240 top and 320×240 touch bottom UI.
- [x] Produce experimental `.3dsx`, `.cia` and `.cci` files for 3DS family via devkitARM and makerom; compilation and packaging only, not hardware verification.
- [ ] Complete Azahar validation on old/New 3DS hardware profiles; CCI title was recognized, but headless screenshots captured a black OpenGL surface and cannot verify the UI. See `docs/RELAY_3DS_AZAHAR_TEST.md`.
- [x] Rebuild preferred `.3dsx` and launch in Azahar 2126.2 on BEN-PC; process started in New 3DS mode; host screenshot shows black GPU canvas and cannot establish rendered UI.
- [ ] Capture a native Azahar in-emulator framebuffer screenshot with desktop UI access, verify both 3DS screens and controller/touch navigation.
- [x] Move native 3DS navigation and Search keyboard to bottom touchscreen while keeping Relay's top screen dedicated to the visual scene/status; rebuild 3DSX.
- [ ] Verify bottom keyboard Shift, Space, Delete, Done and touchscreen/D-pad/A/B/X/Y behavior on Azahar and real 3DS hardware; no such runtime pass yet.
- [ ] Investigate a real 3DS-compatible low-bandwidth video decoder, audio and controller forwarding separately; do not claim full Relay feature parity before tests.

- [x] Add Sony BRAVIA 8 II (XR80M2) Android 12 Google TV-specific Gradle flavor and Leanback manifest, sharing canonical Android Relay code.
- [x] Document `docs/RELAY_ARTIFACT_POLICY.md`: device/universal, OS/framework, packaged ABI and build mode in staged artifact names, and selected-target local package cleanup before each build.
- [x] Add Android selected-target debug APK cleanup and named staging; update Vita/PSTV VPK naming/cleanup.
- [x] Compile BRAVIA 8 II Android 12 multi-ABI APK and record hash.
- [ ] Validate BRAVIA model ABI list, decoding, streamed audio, remote and PS5 session on real hardware.
- [x] Compile PSTV-oriented VitaSDK VPK with correct device/framework/CPU name and record SHA-256.
- [ ] Test compiled VPK on real PSTV over Ethernet with DualShock. Remote video/audio/input remains incomplete.
- [ ] Update all remaining native build scripts (Switch, Windows, Xbox, Apple, Tizen, Roku, Steam Deck) for same artifact naming and scoped pre-build cleanup.
- [ ] Establish immutable versioned GitHub release workflow only when publication is authorized; none yet.
- [x] Consolidate existing local packages by platform under `releases/organized/` with hash-checked `artifact-index.json` and safe source preservation; agent launch failed, direct implementation completed.

Last updated: 2026-11-23. Project root: H:\Samsung Android\PSPortalAndroid.

## UI and controller integration

- [x] Investigate Sony SST typography; use OFL-licensed Inter as a bundled fallback for Relay instead of copying proprietary SST. License retained under assets/fonts/OFL.txt.
- [ ] Tune Inter metrics and multilingual fallback against Portal hardware; exact proprietary SST has not been licensed.

- [x] Add a vector PS5-style controller with label lines identifying all physical key mappings.
- [x] Persist Android gamepad button mappings, analog axes, inversion and dead-zone controls.
- [x] Add Portal-style Wi-Fi and Bluetooth companion menus, honoring Android's required consent.
- [x] Add Portal-inspired 1080/720 screen glyphs and native visual max-resolution chooser.
- [ ] Replace remaining Android Material dialogs with reusable Portal-style fullscreen sheets, except required security/permission dialogs.
- [ ] Audit and correct all touch controls: joysticks, D-pad, L3/R3, triggers, face keys and touchpad on tablet and both Fold layouts.
- [x] Add touchpad press/motion highlight rings to on-screen controls, retaining actual touch coordinates.
- [ ] Test the existing TouchpadView event pipeline; the JB payload currently supports touchpad click but not actual touch coordinates.
- [ ] Add screenshot/layout regression tests for controls, remapper, blur, focus and glass effects.

## PlayTranslate game-video-only translation

- [x] Clone and inspect https://github.com/dominostars/playtranslate (GPL-3.0), available under research/playtranslate-upstream.
- [x] Add opt-in JB video-only PixelCopy, bottom-subtitle OCR and on-device translator pilot. No Relay window capture.
- [ ] Test the translator pilot with owned game footage and PS5 decoded frames; extend to Traditional mode after validation.
- [ ] Do not OCR or translate the Relay app's own menu/settings UI, lock screen, notifications or other applications.
- [ ] Support on-device text recognition, offline/online models with consent, caching, selected game subtitle region and translation overlay.
- [ ] Verify licensing, model packs, user privacy, text quality, latency and game examples; test via owned offline video clips.

## Live spoken captions + concurrent visual Play Translate (all Relay ports)

- [x] Clone InstantCaption at `third-party/InstantCaption`, retain its Apache-2.0 LICENSE/NOTICE, and examine its consented Android playback audio and offline model architecture.
- [x] Add independent own-app playback AudioRecord/MediaProjection pipeline, Android on-device speech recognizer PCM input and ML Kit offline text translator in both Android Remote Play modes, while the existing visual OCR pipeline remains active.
- [x] Add Portal-styled spoken language/original-text settings and a quick-menu action. Android permission prompts are mandatory; capture ends when the streaming activity leaves the foreground.
- [x] Add a simultaneous Japanese visual image + synthesized Japanese WAV speech test page, displaying independent actual visual/audio recognition and translation states.
- [ ] Run the new combined test on the Samsung Tab S11 Ultra with Android playback sharing consent, the needed installed offline Japanese recognizer and ML Kit model. Record both source transcripts and actual translations; treat a missing model or empty speech capture as a failed audio test.
- [ ] Record a verified screen video of both live captions concurrently, install on Samsung, then transfer and verify the result on the Pixel Fold 9 Pro by wireless ADB.
- [ ] Bundle a deterministic, redistribution-cleared Japanese spoken WAV fixture so other Relay ports can test without relying on an Android TTS voice. Runtime TTS fixture generation is currently a device-specific interim solution.
- [ ] Investigate embedding InstantCaption's sherpa-onnx CPU speech engine and model selection/download UX where Android on-device SpeechRecognizer is unavailable. Its NPU runtime and model redistribution require separate license review.
- [ ] Implement capture and locally hosted inference per target platform in `docs/RELAY_PLAY_TRANSLATE_STANDARD.md`; no port should claim audio support until actual decoding and translation succeeds.

## Asobi interoperability and “newtoro” investigation

- [ ] Decompile the user-supplied Asobi.apk and split APKs using jadx/apktool for permitted interoperability and independent implementation.
- [ ] Review capture/codec/input classes of Asobi 2.0 in JADX and document compatible interfaces; no proprietary source should be incorporated.
- [ ] Source APK splits already saved under research/asobi-android-2.0-installed; preserve original files and hashes.
- [ ] Research the user's term “PlayStation newtoro streaming” (not yet identified with confidence). Determine exact API/protocol and failure before claiming any fix.
- [ ] Record Android manifest, capture/codec and controller behaviors and independently implement only lawful compatible interfaces; avoid copying proprietary code verbatim.
- [ ] Compare Asobi foldable touchscreen and dual-screen controls to Relay.

## Setup, full localization, and launcher

- [x] Add first-install language/Wi-Fi/controller/PS5 mode/Finish wizard. Existing installs skip onboarding; not full PSN registration.
- [ ] Add complete PS5 registration/privacy/accessibility first-run parity.
- [ ] Extract all hard-coded Relay UI strings and translate fully, including RTL/accessibility. The setup wizard currently has translated stage headings for 15 locale choices, NOT full app localization.
- [x] Add optional Android ROLE_HOME chooser and PortalHome alias. Existing Portal UI remains the Home display; no app drawer.
- [x] Add Restore Android Home option in System settings.
- [ ] Test setup, Home role and language changes on the original Fold, newer Fold, and Samsung tablet.

## Future real updates

- [x] Local-only simulated System Software update, progress animation and completion.
- [ ] Implement trusted HTTPS release feed, signed manifest, verified checksum/signer, and user-approved Android PackageInstaller.
- [ ] Test download/install failure cases, rollback and confirmation; see docs/UPDATE_DELIVERY_PLAN.md.

## PS5 tests PAUSED

- [ ] Do not resume PS5 live Remote Play, payload, capture or game tests until user requests it.
- [ ] After an authorized clean restart, retest shared video/audio, four input owners and PS5 profile/Guest picker, Samsung first then original Pixel Fold.
- [ ] Verify native PS5 avatar/name association per assigned controller, never hard-code Guest or user accounts.

## Android platform requirement

Stock Android does not grant a normal app authority to silently force Wi-Fi associations, Bluetooth pairing, or permanent Home app role changes. Use user-approved Android panels and role settings from within Relay's Portal-themed UI.
