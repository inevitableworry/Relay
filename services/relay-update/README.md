# Relay signed-update service

This is a **read-only, loopback-bound** origin for signed Relay application
releases. The Cloudflare Tunnel terminates public TLS; the Python server only
listens on `127.0.0.1:18733`. It never runs installers, takes upload requests,
captures a PS5 screen, or handles viewing credentials.

## Release targets and channels

Each target has independent `stable`, `beta`, and `nightly` channels. Supported
target identifiers are declared in `release.py`: `android-mobile`, `android-tv`,
`android-shield`, `android-google-tv`, `android-retroid`, `android-razer-edge`,
`android-ayn-thor`,
`vita`, `switch1`, `windows11`, `rog-ally`, `steam-deck`, `meta-quest`,
`xbox-series`, `xbox360`, `samsung-tizen`, `roku`, `tvos`, `ios`.

The release feed does **not** infer a build's readiness from a file on disk.
An operator deliberately publishes a verified artifact with its correct
version code and target. Unpublished feeds return HTTP 503, including the
first run before any release exists. No demo or fake progress is inserted.

## Setup and publishing (PowerShell on BEN-PC)

Install Python 3.10+ and `cryptography` 41+; install missing dependency with
`python -m pip install -r services/relay-update/requirements.txt`. A signing
keypair is created once, keeping the private key **outside** the repository:

```powershell
$private = Join-Path $env:USERPROFILE '.relay-signing\relay-update-private.pem'
python services/relay-update/release.py keygen --private $private
python services/relay-update/server.py --port 18733
```

On BEN-PC the first keypair has already been generated at the indicated
private-key path. Do **not** rerun keygen or replace `state/public-key.pem`:
that would invalidate the public trust anchor embedded in clients. The
private-key folder has restrictive current-user Windows ACLs. Prefer signing
future public releases on an offline machine and transferring only the signed
manifest and artifact to the server; see the key-custody note below.

After a verified test build is ready for distribution, publish with its real
package ID/version code (the example is **illustrative**, not a claimed build):

```powershell
python services/relay-update/release.py publish `
  --private "$private" --target android-mobile --channel beta `
  --artifact "C:\Path\To\verified.apk" --version 0.3.24 `
  --version-code 38 --package-id "com.portalstyle.remoteplay" `
  --minimum-os 24 --notes "Test-channel update"
```

While the requested nested Cloudflare HTTPS hostname is waiting for edge TLS
certificate coverage, the first-level alias
`https://updates.example.invalid` is a deployment placeholder; configure a real HTTPS endpoint. To stage a
**development beta/nightly release only**, add
`--base-url https://updates.example.invalid` to the publisher command. The
signature covers that chosen URL. Stable releases are deliberately restricted
to the canonical `https://updates.example.invalid` hostname. A client may
select the beta alias only through an explicit developer setting and must
still verify TLS, signature, target, package ID, SDK and downloaded bytes.
No production updates were published during infrastructure setup.

Avoid publishing a debug-signed APK as a production update: the installed
package ID/signing certificate must match the release and the platform must
permit in-place installation. Android package IDs and minimum SDK floors
are checked against the current Gradle flavors before signing. The CLI enforces strictly increasing integer
version codes **within each target/channel**. Stable promotions between
channels need an explicit release signed and published to stable.

Release files are stored under ignored `state/releases/<target>/<channel>/`.
The server refuses unlisted files, verifies the Ed25519 signature before
returning a manifest, and hashes the artifact before returning it. It has no
PUT/POST upload endpoint and no filesystem listing. Client devices must also
verify the entire downloaded binary hash before invoking an installer.

## HTTP API

| Path | Meaning |
|---|---|
| `/healthz` | Process is alive; does not imply a signed release exists |
| `/readyz` | 200 if a verified signed release exists; otherwise 503 |
| `/v1/{target}/{channel}/manifest.json` | Ed25519 signed release JSON |
| `/v1/{target}/{channel}/artifacts/{name}` | Exact checksum-verified artifact named by manifest |
| `/api/v1/updates/android/{flavor}/{channel}` | Android alias for mobile, tv, shield, googleTv, retroid, razerEdge, aynThor and metaQuest |

The example HTTPS base is `https://updates.example.invalid`. Missing feeds
return 503 until intentionally published. Artifact responses use immutable
URLs containing the leading 16 hex characters of the SHA-256 hash.

The signed JSON envelope is:

```json
{
  "payload": {
    "schema": "relay-update-v1",
    "app": "relay",
    "target": "android-mobile",
    "channel": "stable",
    "version": "0.3.24",
    "version_code": 38,
    "published_at": "2026-11-23T12:00:00+00:00",
    "notes": "Release notes",
    "install": {"method": "android-package-installer", "package_id": "com.portalstyle.remoteplay", "minimum_os": "24"},
    "artifact": {
      "name": "<sha256-first-16-hex>-app-mobile.apk",
      "size": 123456,
      "sha256": "<64 lowercase hexadecimal characters>",
      "url": "https://updates.example.invalid/v1/android-mobile/stable/artifacts/<sha256-first-16-hex>-app-mobile.apk"
    }
  },
  "signature": {"algorithm": "Ed25519", "key_id": "dc3d875aa030d831", "value": "<base64 Ed25519 signature>"}
}
```

The private key signs UTF-8 JSON bytes of `payload` serialized with sorted
object keys recursively, no insignificant whitespace (`sort_keys=True`,
`separators=(',', ':')`, `ensure_ascii=False` in Python) and no NaN. The
signature covers all payload fields including URL, package metadata and
version code. The Ed25519 public key is at `state/public-key.pem`; public key
ID = first 16 lowercase hex characters of SHA256(raw 32-byte public key),
currently `dc3d875aa030d831`. Clients must pin the **full** public key; the
short key ID is only a selector, not an authentication factor.

## Client release gates

Clients must fail closed on TLS errors, invalid signatures, wrong target/channel,
unexpected host, malformed metadata, version downgrade/replay, non-matching
hash, unexpected package ID/certificate, API/firmware incompatibility, and
downloads that are interrupted or exceed declared length. Cache the highest
accepted version code per target/channel to block replay even after DNS or
server compromise. Android should call PackageInstaller **with explicit user
confirmation**, report installation success from the platform, and retain the
previous app when validation or install fails. Vita/Switch/Windows and other
platforms require their own verified installer/update flow; listing an NSP,
VPK or EXE here is not proof it can update itself.

Never ship or bundle the private key into a client. Keep the private key offline
for releases, retain an offline backup, and plan a signed key-rotation mechanism
before replacing the currently pinned signing key. Key compromise must be
treated as an incident requiring a client trust-anchor update.

## Verification

```powershell
python -m unittest discover -s services/relay-update/tests -v
python services/relay-update/release.py verify `
  --manifest services/relay-update/state/releases/android-mobile/stable/manifest.json `
  --target android-mobile --channel stable
powershell -ExecutionPolicy Bypass -File scripts/cloudflare/check-relay-network.ps1
```

The manifest verify command requires a published manifest. Test suite uses
temporary keys and never touches production release state.

