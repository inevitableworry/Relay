# Relay gateway

This Node 22 service carries the Android app's authenticated HTTP and WebSocket traffic to one configured PS5 capture service. The production CLI listens only on **127.0.0.1:37841**. A separately managed HTTPS tunnel can point to that loopback listener. The gateway still requires its own Bearer credential on every endpoint except the minimal health check.

No tunnel, DNS entry, startup task, credential, console address or payload is installed by this source package. Deployment supplies those local values. The gateway never exposes the capture service's shutdown endpoint.

## Install and run

From this directory, with Node **22.21.0** and npm available:

```powershell
npm ci --ignore-scripts --no-audit --no-fund
npm test
node .\cli.mjs --config <absolute-configuration.json>
```

From the Android project root the same entry point is:

```powershell
node tools/relay-gateway/cli.mjs --config <absolute-configuration.json>
```

`--config` is the only CLI option. There is no public-bind or port override. The process prints the loopback listening address and bounded JSON lifecycle events. Events contain only allowlisted route names, counters, durations and fixed reason codes; they never include request headers, tokens, controller bodies, configuration contents or payload paths. SIGINT and SIGTERM stop acceptance, close media, finish controller cleanup and settle an active startup attempt.

`createGateway(config, { onDiagnostic } = {})` is exported from `gateway.mjs`. The optional callback receives sanitized event objects; an exception in it cannot affect streaming. Its `listen(port = 37841)` always binds `127.0.0.1`; test callers may use `listen(0)` for an ephemeral loopback port. It returns the Node `server`, `listen()` and an idempotent asynchronous `close()`. Shutdown waits for in-flight native acquisitions and their exact-owner cleanup. `loadConfig(path)` resolves file paths and validates the configuration.

## Configuration

Use `config.example.json` as a field reference and `config.schema.json` for editor validation. The example contains intentionally unusable placeholders. Store the real configuration and persistent journal outside this source directory, for example in the project's ignored `.tools/relay-remote` directory. The local runtime account must be able to read the configuration/payload and create and flush the journal. Restrict those runtime files to the intended Windows account.

| Field | Meaning |
| --- | --- |
| `ps5Host` | Explicit PS5 IP address or hostname; no scheme, path or port. |
| `ps5Port` | Capture service HTTP port. |
| `bearerToken` | At least 32 cryptographically random bytes encoded as printable text. The runtime accepts 32–512 printable, non-space ASCII characters. Write this directly to the private configuration and app; do not put it in URLs or logs. |
| `payload.file` | Exact locally verified ELF to upload, if automatic startup is enabled. |
| `payload.sha256`, `payload.size` | Pinned SHA-256 and exact byte size, checked on the same opened file before network upload. ELF magic is also checked; permitted size is 64 bytes–8 MiB. |
| `payload.loaderPort` | Existing raw ELF loader port. This gateway does not install or exploit a loader. |
| `payload.capturePort` | Capture port implemented by that specific ELF. Must equal `ps5Port`. |
| `startupJournal` | Persistent journal for one permitted automatic startup attempt. Required when `payload` is configured. |
| `timeouts.requestMs` | Entire bounded control/config response, default 3000 ms. |
| `timeouts.probeMs` | Each independent readiness/refusal probe, default 4000 ms. |
| `timeouts.uploadMs` | Maximum local ELF transfer duration, default 10000 ms. |
| `timeouts.readyMs` | Read-only readiness polling after upload, default 12000 ms. |

`payload` may be omitted or null to disable upload while keeping passthrough and already-running-service checks available. JSON file paths resolve relative to the configuration file; the programmatic API requires absolute payload/journal paths. Unknown fields, malformed credentials, invalid ranges and mismatched capture ports are rejected. The schema describes types/ranges; runtime checks additionally enforce the host/path rules and capture-port equality.

## HTTP contract

For protected requests use `Authorization: Bearer <gateway-token>`. Duplicate Authorization headers are rejected. Authentication compares fixed-size SHA-256 digests with `timingSafeEqual`; the credential is never forwarded to the PS5. Only the native `X-PBS-Input` and `X-PBS-Controller` custom headers may be forwarded. Each request targets the configured host/port; request targets cannot select another destination.

| Method and path | Behavior |
| --- | --- |
| `GET /health` | Unauthenticated `{"service":"relay-gateway","version":"1.1.0"}` only. No console or readiness information. |
| `GET /config.json`, `/status.json`, `/input/status` | Bounded native response passthrough. |
| `GET /frames`, `/audio` | Unmodified binary media with stream backpressure and cancellation. Closing the Android stream closes its native request. |
| `POST /video/keyframe` | Native keyframe request; empty body. |
| `POST /capture/profile?height=720\|1080\|1440\|2160` | Select one supported height; empty body. |
| `POST /capture/profile?height=1080&bitrate=12000000&minqp=20` | Optional bitrate and minimum QP must appear together, in this order. Bitrate 1,000,000–100,000,000; QP 18–35. |
| `POST /input/connect` | Requires `X-PBS-Input: 1`; returns the native controller token and starts a bounded attachment lease for that exact owner. Empty body. |
| `POST /input/state` | Requires marker and native token; validates a text state before forwarding. An active WebSocket exclusively owns this token's state channel. |
| `POST /input/disconnect` | Requires marker and native token; empty body. Coordinates with that token's WebSocket cleanup. |
| `POST /relay/start` | Conservative pinned-payload startup below; empty body. |

Unknown paths, unexpected query parameters, encoded/normalized path tricks, wrong methods and upstream redirects are rejected. All POST bodies are forwarded as `text/plain; charset=us-ascii`; a supplied incompatible content type is rejected. Requests are limited to 256 body bytes. Non-state routes require an empty body. Bounded upstream responses are limited to 64 KiB; media is streamed directly and has no response buffering limit.

## Ordered WebSocket controller input

Open the externally secured **WSS** equivalent of `/relay/input` with the gateway Bearer header and the native **32-hex-character** `X-PBS-Controller` token obtained from `/input/connect`. The internal loopback hop is HTTP/WebSocket. There is no token in the URL and the upgrade does not acquire a new native controller owner.

Send text messages containing exactly eight unsigned decimal fields:

```text
seq,buttons,lx,ly,rx,ry,l2,r2
```

`seq` and `buttons` are uint32; each analog value is 0–255. The native payload retains its own supported-button validation. Sequence numbers must increase in uint32 modular order, allowing wrap from 4294967295 to 0. Binary messages, oversized bodies, duplicate/out-of-order sequence numbers and queue overflow close the channel with a short, nonsecret reason.

One worker sends native POSTs sequentially. It keeps a changed digital button state for at least **24 ms after its native HTTP 200 acknowledgement** before sending the next digital change. This preserves short down/up taps received together after cellular buffering. The queue is bounded to 64 states including in-flight work; a state waiting more than one second closes/cleans the channel rather than replaying an old combo. `ok,<seq>` always refers to the client's sequence and is sent only after native acceptance. Native sequence numbers are assigned independently so neutral preparation/recovery heartbeats cannot collide with client numbering. Acknowledgements are bounded against a blocked client.

On close/error, pending unsent states are discarded. An in-flight native POST finishes first, then a fresh neutral state is sent and the **same native token** is disconnected. The gateway never calls `/input/connect` during cleanup. An HTTP disconnect arriving during this cleanup waits for it; repeated cleanup for a completed token reuses a bounded cached result for 60 seconds. If the LAN fails, a successful disconnect is not claimed. The service allows at most four active controller channels and 64 total inbound TCP connections.

Native PBS expires an idle controller after two seconds. A gateway-created owner receives an immediate, verified neutral preparation before its token is returned, then neutral-only keepalives every 500 ms while its client opens WSS. The attachment lease expires after 15 seconds, subject to event-loop scheduling. A lost HTTP response or failed upgrade cleans up only the returned exact token. Transfer waits for an in-flight neutral POST, then carries its native sequence into the WebSocket worker. A token that already expired before the gateway received it fails preparation; success is not fabricated. Ambiguous native acquisitions are never automatically resent. HTTP state clients renew the same bounded lease after native acceptance.

During a short input-path silence, the active channel sends only neutral keepalives, never its last gameplay state. Pings/checks run every five seconds. A 15-second lack of peer traffic **or client input** is detected on the next check, so cleanup starts approximately 15–20 seconds after silence, plus bounded native cleanup time. Native input is neutralized by its own 250 ms inactivity protection before the next 500 ms neutral keepalive.

Diagnostics distinguish media opening/closure, upstream errors, downstream cancellation, owner acquisition, lease expiry, input closure and cleanup status. They are limited to 120 events per minute plus a suppressed-count summary. Paired downstream video/audio closure can be an intentional app reconnect; it does not by itself identify a tunnel or DNS fault.

## Conservative startup and journal recovery

`POST /relay/start` first checks `/config.json`. A valid PS5 configuration returns `{"state":"running"}` without mutation, even if an earlier journal exists.

Only **three separate, explicit ECONNREFUSED results** can authorize a new upload. Two preliminary probes must refuse. The gateway then verifies the pinned file, exclusively creates the journal, flushes its `armed` record, and performs a fresh final probe. It flushes the three-refusal record before connecting to the loader. HTTP answers, redirects, timeouts, DNS failures, resets or mixed connection errors never authorize upload. If a compatible service appears, it is reused.

Concurrent startup calls share one operation. Exclusive journal creation also prevents a second process using the same journal from uploading simultaneously. The ELF is uploaded once to the configured loader; completing a local socket write does **not** prove remote execution. The gateway then polls the capture service read-only. A verified compatible configuration returns `{"state":"started"}`.

The journal remains after success, transfer failure, timeout or ambiguous outcome. A later absent service with an existing, even partial, journal returns HTTP 409 and requires local operator review; it does not upload again automatically. There is no remote journal-reset or shutdown endpoint. After reviewing console/service state and intentionally authorizing another attempt, the local operator can archive that journal and use a fresh attempt file. Keep the journal across process restarts and OS logons.

The default upper startup budget is approximately **34 seconds** (three 4-second probes, a 10-second upload and 12 seconds of readiness polling), plus small local file operations. The Android caller should allow at least 45 seconds. Error responses distinguish unconfigured upload (501), recorded attempt (409), unverified startup/integrity (503), and ambiguous/unavailable upstream responses (502/504) without disclosing paths or secrets.

## Validation and limits

`npm test` uses local fake HTTP services, a local fake ELF loader and ephemeral loopback gateway ports. Test files and journals are created only under ignored `.test-runtime` and removed after their tests. No test reaches a console, tunnel or public hostname. All 39 tests passed at the recovery handoff. Tests exercise authentication, allowlisted queries, bounded bodies, header stripping, byte-for-byte media/cancellation, real WebSocket ordering/short taps/cleanup, sequence wrap, queue limits, delayed attachment, lost acquisition replies, shutdown barriers, invalid upgrades, exact-token spelling, bounded diagnostics, pin checks, exclusive journal creation, ambiguous startup and retry protection across restart.

These tests establish gateway protocol behavior. WAN reachability, tunnel lifecycle, real PS5 loader behavior, actual capture quality and physical controller input require the separate root deployment/device validation. The gateway does not add codecs, resolutions, capture/recording APIs or native input capabilities absent from the installed payload.

Implementation references: [Node 22 HTTP](https://nodejs.org/docs/latest-v22.x/api/http.html), [Node streams](https://nodejs.org/docs/latest-v22.x/api/stream.html), [file handle sync](https://nodejs.org/docs/latest-v22.x/api/fs.html#filehandlesync), and [ws](https://github.com/websockets/ws). Native route contracts were read from this project's `.tools/pbs-upstream/payload/src/common/http/controller_routes.h`, `profile_controls.h`, and `common/input/controller.c`; no payload source is copied into this JavaScript package. The pinned dependency license is retained in `THIRD_PARTY_NOTICES.md`.
