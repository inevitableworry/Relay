# Relay for Steam Deck / SteamOS — native-client launcher adapter

This dedicated SteamOS package uses the same PS Portal visual assets and
animated home interface as the current Windows Relay Canvas shell. It runs an
HTTP server **only on 127.0.0.1**, opens a fullscreen Chromium app window
when available, and uses gamepad A-button for Connect.

The **real streaming process** is currently the installed native
`chiaki4deck`, `chiaki-ng`, or `chiaki` executable (or a user-supplied
`RELAY_STEAMDECK_BACKEND` executable path). It is **not embedded** in this
special build. The launcher never presents the static shell as a fully
functional independent PS5 decoder. Full native Relay Linux integration
remains planned.

```bash
cd relay-steamdeck
chmod +x run-relay.sh
./run-relay.sh --check
./run-relay.sh
```

Steam Deck Gaming Mode: copy the directory to the Deck's home area, add
`run-relay.sh` as a non-Steam game, and set Steam Input's A/B/LB/RB to the
web UI controls. The desktop file is included for KDE desktop shortcuts.

Not supported yet: Steam Deck GPU/codec telemetry, complete Vulkan hardware
video decoder hosted inside Relay, local PS5 media gallery integration,
native game audio in the UI process, Steam Deck-specific Quick Access hooks,
and authenticated social/spectate integration. The upstream Chiaki package
handles genuine PS5 streaming when installed/configured.

The package includes no tokens or passwords. The status API is loopback-only,
requires a random in-memory CSRF token to launch the external client and
never runs arbitrary commands supplied by the browser.
