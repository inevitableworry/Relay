# Relay — Xbox Series S / Series X Developer Mode edition

**Build state (November 23, 2026):** Real neutral UWP `.appx` built using the installed Windows SDK 10.0.26100.0, signed with a new local development-only certificate, unpacked and structurally inspected, and HTML/Canvas rendering checked in BEN-PC's Edge browser. **Not yet installed or launched on a physical Xbox.** This is a new Relay UWP **development edition**, not a complete PS5 Remote Play streaming port.

## Why UWP

Microsoft explicitly supports UWP applications through the Xbox Developer Mode environment on both Xbox Series S and Xbox Series X. The installed Visual Studio 2022 Community on BEN-PC has the Windows SDK packaging utilities but no `Microsoft.Windows.UI.Xaml.CSharp.targets`; this build instead uses the supported packaged HTML/JavaScript UWP application type (`Application StartPage="default.html"`). It is **not** a Windows desktop Win32 app and **not** an Xbox GDK retail game. `ProcessorArchitecture="neutral"` shares the same package across Series S and Series X.

## Contents and features

- Original Relay menu artwork, source-derived navigation graphics, black/purple background, spinning 2,600-particle sphere, 19 independently rotating/hazed particle clusters, glow sprites, startup ring, and cloud/floor radial gradient effects ported from Android's `PortalBackdropView.kt`.
- Original OGG cue sources converted into PCM WAV for Xbox's legacy UWP HTML media engine; source stereo PCM home music is wrapped losslessly in a WAV header. No unrelated sounds, extracted Sony assets or alternate remote-play UI.
- Relay-style quick-menu, Remote Play / Cloud / Search tabs, 10-foot keyboard/gamepad navigation using `Windows.Gaming.Input.Gamepad`, and editable saved PS5 LAN address.
- Read-only connection to the **user's own Relay PS5 PBS** `/config.json` endpoint, using UWP `Windows.Web.Http.HttpClient`. Parsing and size limits are adapted independently from Relay's `PbsProtocol.kt`; test coverage in `tests/pbs-protocol.test.cjs`.
- **No active PS5 streaming** in this developer build: H.264 media-pipeline decoding, controller ownership/session transport, streamed game audio and capture recovery require porting and an available PS5 to test. Connect currently checks availability and explicitly reports this limitation. Cloud Streaming and device discovery are similarly not implemented. No console payloads or recovery locks are changed.

## Build and install files

From the Relay repository root on BEN-PC:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\platforms\xbox-series\build-xbox.ps1
```

Generated artifacts:

```text
platforms/xbox-series/dist/Relay-Xbox-Series-S-X-Dev_0.1.0.0_neutral.appx
platforms/xbox-series/dist/Relay-Xbox-Dev-Public.cer
```

The local private signing key stays in **CurrentUser/My** on BEN-PC and is never exported to the repository, build or user. The `.cer` file contains only the public certificate, which may need to be trusted by the destination device. SignTool reports a **valid APPX signature but an untrusted self-signed certificate chain** unless the publisher certificate has been trusted. This is expected for a local dev-signed build, and is not a Microsoft Store signature.

To deploy on a Series S or X, the Xbox must first be configured for **Developer Mode**. Use its **Xbox Device Portal** to sideload the APPX and any required dependencies, and follow the device's developer certificate/trust process. Do not use Retail Mode or attempt to install it as an Xbox GDK/retail game. Deployment may require the developer to sign the package with a different trusted identity. The user's Xbox has not been connected in this session, and no credentials were requested.

## Validation performed

1. `node --check` passed for both main UWP JavaScript files.
2. `node --test` passed **4 tests** for Relay PBS framing, remote configuration validation, host validation and controller field encoding.
3. Original-source-backed UWP assets and 2,600 seeded particles were generated; the build retains file SHA-256 provenance in `ASSET_PROVENANCE.json`.
4. Windows SDK `MakeAppx pack` and `MakeAppx unpack` both succeeded. The unpacked manifest identifies the architecture-neutral `Relay.Xbox.Series.Dev` UWP application and its `default.html` start page.
5. Windows SDK `SignTool sign` succeeded. Chain trust verification failed **only because the development certificate is self-signed and not trusted by the Windows trust provider**.
6. BEN-PC Edge headless preview successfully rendered the animated sphere, source navigation artwork and menu text at 1920×1080 (`build/relay-xbox-web-preview-final.png`). This is a **web-renderer preview**, not an Xbox hardware test.

## Original-source parity limitations

The particle coordinates use the same seeded Java Gaussian layout as the Android app. HTML Canvas color stops and timing reproduce the Android source, but frame-by-frame shader, motion and audio parity have **not** been verified on Xbox hardware. The Xbox JavaScript UWP runtime is not equivalent to current desktop Edge: it needs a real-device playback/input test. The app is not a complete native chiaki/libchiaki Xbox port and does not pretend to deliver remote play video without decoding.

## Authoritative platform guidance

- [Microsoft: App compatibility for Xbox Series S/X](https://blogs.windows.com/windowsdeveloper/2020/09/17/app-compatibility-for-xbox-series-x-and-xbox-series-s-consoles/)
- [Microsoft: Xbox UWP development options](https://learn.microsoft.com/en-us/windows/uwp/apps-for-xbox/development-options)
- [Microsoft: Gamepad API for Xbox UWP](https://learn.microsoft.com/en-us/windows/uwp/gaming/gamepad-and-vibration)
- [Microsoft: Manual UWP APPX/MSIX packaging](https://learn.microsoft.com/en-us/windows/uwp/packaging/)
