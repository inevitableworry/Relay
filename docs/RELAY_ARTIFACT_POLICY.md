# Relay artifact naming and cleanup policy — 2026-11-23

**GitHub preparation (2026-11-23):** All locally generated Relay
debug/release packages and verified build-output directories were cleaned
from the working tree before publication (9.37 GiB selected). Source,
build scripts, canonical assets and QA screenshots were retained.
Build locations and hashes in earlier sections are historical, not promises
that package files remain present. Consult docs/GITHUB_READINESS_2026-11-23.md
and the ignored local cleanup receipt before rebuilding.

Status: local development only. No GitHub release is authorized or published.

## Every platform and build configuration

Use `Relay-<device-or-universal>-<OS-or-framework>-<actual-packaged-ABI-or-CPU>-<debug|release|dev>.<extension>` for final staged artifacts. A multi-ABI Android APK lists all bundled ABIs, e.g. `armeabi-v7a_arm64-v8a`. An architecture-independent package uses `neutral`. The named file must reflect the actual package, never a guessed chipset, and its SHA-256 must be recorded. Extensions reflect platform: Android/Google TV `.apk`; PS Vita/PSTV `.vpk`; Switch `.nro`/`.nsp`; Windows `.exe`/`.zip`/`.msix`; Xbox `.appx`/`.xex`; Apple `.ipa`; Samsung Tizen `.wgt`; Roku `.zip`; SteamOS `.AppImage`/`.zip`.

Before each local build, remove **only** previously generated debug/release/dev packages for the selected target from dedicated build-output and local staging directories. Do not delete source, Git metadata, Gradle/SDK caches, keystores, test screenshots, logs, other platform outputs, installed apps, saves, user data, or previous published GitHub releases. Verify any recursive delete path is inside this project's build output tree. On failure, report missing new artifact rather than serving an old one. Do not publish to GitHub automatically; when authorized later, create immutable versioned release assets.

The Android `build-android.ps1` now cleans selected flavor `android/app/build/outputs/apk/<flavor>` and matching named local-development APKs, then copies a fresh named APK to `releases/local-development/`. The Vita `build-vita.sh` cleans its designated old VPKs before repackaging as `Relay-PSVita-PSTV-ARMv7-VitaSDK-dev.vpk`. All other native platform scripts require their own implementation of this rule; a written rule does not prove automatic cleanup.

## BRAVIA 8 II

Sony XR80M2 (BRAVIA 8 II) runs Google TV / Android 12. The `bravia8ii` Android flavor has its own `com.portalstyle.remoteplay.bravia8ii` ID, Leanback landscape UI and minimum API 31. Include both ARMv7 and ARM64 initially until `adb shell getprop ro.product.cpu.abilist` on physical hardware establishes the device's process ABIs. Verify MediaCodec, audio, remote/D-pad, controller and real stream rather than assuming that build success equals compatibility.

Example: `Relay-Sony-BRAVIA-8-II-AndroidTV12-Android-armeabi-v7a_arm64-v8a-debug.apk`.

## PS Vita / PlayStation TV

Keep our own existing VitaSDK-native Relay renderer and logic; do **not** transplant the Android APK or substitute VitaRPS5 code. PSTV is the first hardware validation target using wired Ethernet and DualShock. The same Vita-native VPK may work on handheld Vita, but touch/controller/graphics differences require testing, and its name currently denotes the initial PSTV testing profile, not a uniquely optimized PSTV executable. Existing VPK is a UI/menu prototype. Actual streamed video, game audio and remote input remain development work.

## Universal dual-screen Android and 3DS family

`dualScreen` Android flavor targets all capable Android devices exposing an independent second `DisplayManager` display (ARMv7/ARM64). Its primary UI and secondary control screen use standard Android APIs and no AYN-specific hooks. The prior `aynThor` flavor remains compatible for existing installs, but the preferred future build is the universal flavor. Devices exposing only one combined display cannot present a second independent UI using this path.

The Nintendo 3DS XL and New Nintendo 3DS XL share one native ARM11-targeted source producing experimental `.3dsx`, `.cia` and `.cci` artifacts. The `.cci` extension denotes cartridge images, not a newer successor to `.cia`. No Android APK runs on 3DS.

**Latest 3DS build status:** `platforms/3ds/build-3ds.ps1` generates the shared `.3dsx`, CIA installer and decrypted CCI cartridge-format image using devkitARM and `makerom`, and clears all three local previous target packages before building. These three output formats contain the same experimental UI source, not three independently validated applications. Actual old/New 3DS emulator rendering, streaming and physical compatibility are unverified.

**One build collection:** `releases/organized/<platform>/` stages known local build outputs, with hashes/source paths in `releases/organized/artifact-index.json`. Refresh via `powershell -ExecutionPolicy Bypass -File scripts/collect-relay-builds.ps1 -UseHardlinks`. The collection does not delete source artifacts, alter installed programs or publish GitHub assets. The latest organizer run on BEN-PC identified 26 distinct package source entries after archiving old 3DS formats; many historical native scripts still need dedicated pre-build cleanup migration.
