# Relay Android TV and NVIDIA Shield TV

The same Relay remote-play UI and icon artwork are shared by three independently installable application flavors. The two TV variants use a landscape launcher and a 1280 x 720 launcher banner derived from the original Relay icon, without changing the phone icon.

| Variant | Application ID | Build command | Output |
| --- | --- | --- | --- |
| Android phone | `com.portalstyle.remoteplay` | `./build-android.ps1` | `android/app/build/outputs/apk/mobile/debug/app-mobile-debug.apk` |
| Android TV | `com.portalstyle.remoteplay.tv` | `./build-android.ps1 -Tv` | `android/app/build/outputs/apk/tv/debug/app-tv-debug.apk` |
| NVIDIA Shield TV | `com.portalstyle.remoteplay.shield` | `./build-android.ps1 -Shield` | `android/app/build/outputs/apk/shield/debug/app-shield-debug.apk` |

## NVIDIA Shield support target

The Shield flavor includes native ARMv7 (32-bit) and ARM64 libraries to cover the 2019 cylindrical Shield TV (32-bit Android), 2019 Shield TV Pro (64-bit), and 2015/2017 Shield TV and Pro boxes (64-bit). Android API 24+ is required. The manifest advertises Android TV/Leanback and no touchscreen requirement. This is a targeted compatibility build, **not yet a claim of on-device validation on each Shield revision**.

The app still needs a controller for actual PS4/PS5 play. A Shield remote can navigate menus; it does not substitute for all buttons on a gamepad. Streaming fidelity, surround audio, controller mappings, latency and standby behavior must be tested on real Shield hardware before production release.

The TV flavors intentionally have distinct package IDs, so installing them will not overwrite the phone Relay app. Each uses a separate app data store and console pairing must be performed again. Debug APKs are for local testing, not production distribution.
