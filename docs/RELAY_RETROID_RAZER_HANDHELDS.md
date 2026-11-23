# Relay Android handheld editions — Retroid and Razer Edge 5G

Updated November 23, 2026. These are separate, native Android APK product flavors of the existing Relay application; they do **not** replace the PS Portal-style UI, artwork, audio cues, animations, or PS5 connection implementation. They are not ports of other Remote Play apps.

## Universal Retroid edition

- **Flavor:** `retroid`
- **Package:** `com.portalstyle.remoteplay.retroid`
- **Output:** `android/app/build/outputs/apk/retroid/debug/app-retroid-debug.apk`
- **ABIs:** `armeabi-v7a` and `arm64-v8a`, providing broad compatibility with Pocket 3+ and both 32-bit and 64-bit Android installations. Android SDK minimum: 24 (Android 7.0).
- **Runtime detection:** identifies Pocket Duo, Pocket 3+, or generic Retroid family when reported in `Build.MANUFACTURER`, `BRAND`, `MODEL` or `PRODUCT`, while preserving normal Android gamepad/display detection for unknown Retroid product names.
- **Handheld Compatibility page:** System Settings → Handheld Compatibility reports available display modes and refresh rates, attached Android controllers, supported ABIs, low-RAM flag and Android version. Uses Android's `DisplayManager` rather than guessing a two-screen configuration.
- **Pocket Duo:** supports multiple Android displays when the firmware exposes them. An optional secondary-screen status presentation opens only on a discoverable `DISPLAY_CATEGORY_PRESENTATION` display. It does not move Remote Play to another display or assume the second panel exposes a standard Android secondary-display API. Retroid-specific hardware controls, power/fan controls and multiple concurrent game views are **not implemented**.

The requested Pocket Duo design target includes the user's specified Qualcomm Q-7790, 8 GB RAM, 1080p/960p 120 Hz dual AMOLED screens, and analog triggers. Those target specs are **not hardcoded as observed hardware properties**. Relay detects real screens and controllers at runtime and uses Android's platform video decoder for H.264. This avoids misidentifying other Retroid models or claiming every Retroid firmware exposes the same screen APIs.

## Dedicated Razer Edge 5G edition

- **Flavor:** `razerEdge`
- **Package:** `com.portalstyle.remoteplay.razeredge`
- **Output:** `android/app/build/outputs/apk/razeredge/debug/app-razeredge-debug.apk`
- **ABI:** `arm64-v8a`; Android SDK minimum 24.
- **Presentation:** landscape-first on the PS Portal boot/home/settings/stream screens, with the original Relay gamepad controls and audio/animation assets preserved. The same Handheld Compatibility page reports the actual Razer Edge 5G's connected gamepad, display and Android capabilities.
- **Recognition:** detects the Razer Edge family and hardware `RZ45-0460` identifying the 5G variant. It avoids assuming all Razer Edge devices have cellular hardware.

## Build and validation

Build separately on BEN-PC with the normal CMake/Gradle toolchain:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build-android.ps1 -Retroid -RunTests
powershell -NoProfile -ExecutionPolicy Bypass -File .\build-android.ps1 -RazerEdge -RunTests
```

Both APKs built successfully. Each flavor passed **150 JVM unit tests with zero failures** on November 23, 2026. The Retroid APK contains both 32-bit and 64-bit ARM JNI libraries; the Razer Edge 5G APK contains ARM64 libraries. Both APKs installed successfully on a connected Samsung Galaxy Tab S11 Ultra as a basic package-install smoke check. **Neither has been validated on actual Retroid or Razer Edge hardware in this session.** In particular, native dual-screen presentation, 120 Hz rendering, controller trigger travel, cooling and firmware-specific controls still need hardware testing.

The builds have distinct application IDs from the ordinary Mobile and TV Relay editions, so they do not overwrite those installations. Debug builds use Gradle's development signing; release signing is not implied.
