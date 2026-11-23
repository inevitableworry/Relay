# Relay Meta Quest panel build

The distinct Android product flavor `metaQuest` produces
`android/app/build/outputs/apk/metaQuest/debug/app-metaQuest-debug.apk`
with application ID `com.portalstyle.remoteplay.quest` (ARM64).

## Exact supported device targets

- Meta Quest 2 (`quest2`)
- Meta Quest Pro (`questpro`)
- Meta Quest 3 (`quest3`)
- Meta Quest 3S (`quest3s`), including 3S Xbox Edition.

This flavor is a **2D Horizon OS spatial window**, *not yet a native stereo
OpenXR/Meta Spatial SDK immersive VR client*. Existing Relay screens, Android
input and PS Portal visuals remain canonical. Horizon OS places 2D apps in
panels in 3D space, and user can resize/move the window. Do not claim
passthrough, eye tracking, native motion controllers, two-eye texture rendering
or theater-mode video without implementing and testing the actual VR backend.

Build:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build-android.ps1 -MetaQuest
```

Developer Mode and ADB sideloading are required for testing on physical Meta
Quest; in Horizon OS, open the app from Library → Unknown Sources. A Quest
headset has **not** been detected/verified here. Also verify whether the
existing Android ML Kit components have Google Play Services requirements on
Quest and show a clear error if unavailable. Do not expose the Quest app to
public store distribution as a finished headset-native product.

The Android manifest deliberately does **not** declare
`com.oculus.intent.category.VR`, because Meta's current specification
reserves that launcher category for **immersive** applications and requires a
separate render path.

Official references:
- https://developers.meta.com/vr/documentation/android-apps/create-app/
- https://developers.meta.com/vr/documentation/native/android/mobile-device-setup/
- https://developers.meta.com/vr/documentation/native/android/mobile-native-manifest/
