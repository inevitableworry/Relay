# Relay PS5 host and spectate prototype

This is a local, non-PS5 **control-plane simulator**. It is not a jailbreak
payload, does not capture game video/audio, and cannot stream, read console
profiles or modify PS5 avatars. See ../../docs/PS5_HOST_SPECTATE_SPEC.md.

Current functionality: loopback-only HTTP server and PS Portal-styled setup
interface with a local setup PIN; central Relay Social pairing calls; an
expiring QR pairing code; approval/poll status; private bearer token stored
only after approval; local privacy preferences with streaming forcibly OFF.

Run with Relay Social on 127.0.0.1:48175:

~~~powershell
cd 'H:\Samsung Android\PSPortalAndroid\platforms\ps5-spectate-host\prototype'
python host.py --social http://127.0.0.1:48175 --port 48176
~~~

Open http://127.0.0.1:48176 and enter the one-time PIN printed in the
terminal. Pair opens a 10-minute QR and code; approve it in Relay Social at
the configured /pair route. Requires local Python qrcode/Pillow package.

Security boundaries: local-only service, no open PS5 ports, no PSN credentials
or permanent tokens inside QR, no automatic audio/video capture, no MAC as
reset credential, separate viewer consent required for future spectating.
Private .local-state/ is excluded from version control.

Next project phases: actual jailbroken PS5 user-profile metadata inspection,
owner-authorized capture primitive, session-limited outbound authenticated
signaling and WebRTC/TURN/SFU media gateway, per-viewer revocation. None is
implemented or verified yet.
