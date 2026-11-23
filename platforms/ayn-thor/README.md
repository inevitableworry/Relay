# Relay for AYN Thor dual-screen handheld

The `aynThor` Android flavor produces
`android/app/build/outputs/apk/aynThor/debug/app-aynThor-debug.apk`,
application ID `com.portalstyle.remoteplay.aynthor` (ARM64 Android 13+).

Native AYN Thor has two Android display panels. Relay keeps the **canonical
Android PS Portal UI and stream output on the upper/primary display** and
creates a separate `Presentation` with a PS Portal-colored lower-screen
touch control deck when the second display is available.

The lower deck provides real callbacks into the current Relay activity:
Connect, Remote, Cloud, Search, Quick Menu and Settings. The controls have a
short double-tap guard, do not require root/Shizuku/accessibility privileges,
and do not alter Android vendor display settings. On a single-display device,
the deck is absent rather than covering the video.

Actual AYN Thor hardware is **not** connected. The device can expose panels
in different configurations or firmware; a `Presentation` only works when
the lower panel appears as a distinct display in `DisplayManager`.
Full dual-screen parity, IME placement and independent gamepad focus must be
verified on an AYN Thor in split/extended mode.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build-android.ps1 -AynThor
```

Relevant source:
`android/app/src/main/java/com/metallic/chiaki/main/PortalThorSecondScreen.kt`

References on vendor panel handling:
- https://github.com/mnymkr/thor-nova
- https://github.com/slushiimusic/ThorBottomScreen
