# Relay native Create and encoder quality controls

## Party Mode v4: bounded controller health and working-load path

The last hardware-working firmware 10.01 controller initialization used
the full eight-library PS5 runtime dependency set **without** an explicit
libSceMbus dlopen. This v4 build preserves that original sequence by
default. The experimental Mbus preload is retained only behind the
explicit PBS_PS5_FORCE_MBUS_PRELOAD build define for diagnostics.

The earlier controller initialization hang also exposed a server-level
problem: native connect held the primary controller mutex and the player
admission mutex. Every later /input/status, /party/status and party
connection could accumulate a blocked HTTP worker until PBS stopped
answering unrelated /config.json requests. All status/authentication/
party admission paths now fail immediately with HTTP 503 when the
corresponding controller lock is occupied. This keeps the streaming
diagnostic service responsive while native pad initialization is blocked
and prevents unintended duplicate controller allocation attempts.

The new offline stalled-backend test deliberately blocks native
controller creation, verifies that /config.json still responds normally,
and confirms /input/status, /party/status and a second /party/connect
return HTTP 503 before releasing the simulated native call. This
test has no console access and cannot establish successful PS5 pad
initialization; hardware verification remains required.

On Android, the optional read-only Payload Manager process probe at
port 8084 prevents an automatic ELF upload when it observes a resident
payload.elf, pbs.elf or pbs-ps5.elf process with the PBS HTTP service
absent. The probe distinguishes an unavailable/malformed process API
from an actual empty process list. The existing capture recovery and
uncertain-upload protection remain in effect.

## Party Mode payload refresh (2026-11-22)

### Live virtual-controller initialization follow-up

### Reproduced controller initialization build regression (v3)

The last hardware-working `pbs-relay-quality.elf` had eight dynamic PS5
libraries in its `DT_NEEDED` list. The failing Party Mode v1 and v2 Linux
builds accidentally used the SDK compiler's **minimal default set of only
three**: `libkernel_web`, `libSceLibcInternal` and `libSceNet`.
The omitted libraries were `libSceRegMgr`, `libSceUserService`,
`libSceRemoteplay`, `libSceSystemService` and `libSceAudioOut`.
The omission changed which libraries were loaded by the process before
`scePadInit`, and is a strong explanation for why the earlier firmware
10.01 controller worked while the new one stopped after
`Load libScePad rc=0x2023`.

The `payload/build.sh` PS5 path now explicitly links the same eight
libraries that the previous hardware-working Windows build linked.
It fails immediately if any library stub is absent. The offline wrapper
`tools/pbs-party-dev/build-party.sh` also runs
`verify-runtime-libs.py` to reject any future ELF that drops one of
these dependencies. The validator was exercised against both binaries:
the old failing v2 ELF **fails** with precisely the five missing libraries,
and the corrected v3 ELF **passes** with all eight.
The earlier Mbus preload and detailed Pad log checkpoints are retained
as the other possible missing prerequisite. The controller's account and
four-player code remain intact.

This fix is confirmed at compile/link and offline regression-test levels,
and must not be claimed hardware-working until its ELF is loaded after
the currently hung v1 process (PID 100) has cleanly exited or the PS5
has actually restarted.

A single production ELF upload opened PBS video at 1080p/60 fps on
192.0.2.10:9999, and `/party/status` advertised four players. The
subsequent Samsung Player 1 connection blocked during native ScePad setup:
`/data/pbs/controller.log` ended after loading `libScePad.sprx`; the
service stopped responding to HTTP while the payload process remained
resident. No second ELF was uploaded.

The staged **v2** native adapter preloads `libSceMbus.sprx` with global
symbol visibility before resolving `libScePad`, and logs entry/exit
for the `scePadInit` lookup and call. This follows the documented
PS5 virtual-device dependency requirement in the public octet PS5 source:
`https://github.com/sub0x00001/octet/blob/main/src/ps5_vpad.c`.
If Mbus cannot be loaded, controller registration returns an explicit
failure without calling ScePad. This change passes offline native backend
tests and an ELF structural check. It is **not** hardware-validated yet.

Payload Manager reported one live `payload.elf` process (PID 100) with
capture resources still potentially owned. The v2 ELF was not installed
over that process. Avoid sending another copy until the running service
has cleanly exited or the console has been restarted.

The current bundled PS5 ELF now has staged Party Mode source built against
**ps5-payload-dev/sdk v0.45**, using **clang-18** on Ubuntu/WSL.
This corrects the incompatibility seen when trying to compile against the
obsolete SDK v0.10, which lacked required kernel APIs.

The native payload adds a serialized `/party/connect` admission route. First
successful connection becomes Player 1 (the original virtual pad), followed
by independent virtual pads for Players 2–4. Each session has its own token,
state sequence, idle timeout and cleanup. The stream encoder and audio source
are shared. The payload defaults explicitly to **1920×1080**, avoiding the
upstream 1440p default and the previous 2160p failure mode.

New authenticated read-only endpoints can expose the names and locally
cached avatars of *currently logged-in* PS5 users to active controller
sessions. They do not assert which user owns a particular virtual pad.
User/Guest selection remains the console's native prompt; automatic user
binding and four-controller versus play require hardware verification.

The original pinned source archive and quality patch remain available for
provenance. The complete merged source is `jb/pbs-party-source.tgz`, with
the patch relative to the original baseline in `jb/relay-party.patch`.
Rebuild tools and offline test sources are under `tools/pbs-party-dev/`
in the development tree. Hashes and toolchain are recorded in `payload.json`.

**No PS5 deployment/4-player hardware claim is made by offline compilation.**
The Android capture quarantine for the prior failed native 2160p open remains
in force. A confirmed PS5 restart and capture-recovery confirmation are
required before attempting to upload this revision.

---

Base: [PBS-Payload d66453a68a72e7061bad2bb1d0a3fd9dcb27b2c0](https://github.com/seregonwar/PBS-Payload/tree/d66453a68a72e7061bad2bb1d0a3fd9dcb27b2c0).
License: GPL-3.0-or-later; the upstream LICENSE is retained.
Patch revision: `relay-native-create-quality-v2`, 2026-11-22.

The complete archive contains the actual modified source used for the bundled ELF.

Native button changes preserve the prior Relay correction: allow Share/Create bit0x1,
advertise explicit button masks, and correct the embedded browser's PS/Create mapping.
The source remains compatible with older height-only clients.

The quality extension exposes the existing native bitrate/budget and minimum-QP
controls through an atomic capture-profile transaction:

```
POST /capture/profile?height=1080&bitrate=12000000&minqp=18
```

The strict parser bounds bitrate to1–100 Mbps and QP to18–35, rejects malformed or
partial controls and serializes profile requests. GET /config.json advertises
rate_control.version1, accepted and pending values only after successful native
pre-start SetVideoBitrate and capture startup. Accepted targets are not claims
that variable-bitrate output matches them.

The reachable production path applies bitrate before Start. The upstream static
bitrate_after_start flag is initialized to zero and never assigned by avserve;
this patch does not claim transactional support for that dormant diagnostic branch.

Reconfiguration retains the existing owned capture/audio cleanup and safety gate.
Rejected tuning restores the prior requested tuple. A failed OpenVideo before
acquiring a real handle remains quarantined, and the source never weakens cleanup
requirements to reopen it. Firmware selector, injected call ABI and native
controller ownership are unchanged. Unknown firmware still fails admission.
No reboot, unrelated-process control or native-recording API was added.

The native button masks are independently documented by Ghostcontrol source pinned
to9b7023b44427bab7aaebeb8d0f1b3909cc19c420, specifically core/src/bt/pad.h,
core/src/gc_types.h and the unmodified insertion in core/src/bt/ps5_vpad.c:
<https://github.com/StonedModder/Ghostcontrol-PS5-USB-Controller-Patcher/tree/9b7023b44427bab7aaebeb8d0f1b3909cc19c420>.

Build on Windows with the verified PS5 Payload SDK, Clang and Python on PATH, using
a checkout alias without spaces:

```powershell
.\relay-build-quality.ps1 -SdkPath 'C:\path\to\ps5-payload-sdk'
python relay-package.py
```

The build wrapper uses only a vacant temporary R: SDK alias, restores environment
and removes its own alias. It builds the1080p/controller-enabled payload, validates
it with upstream's structural ELF verifier, and accepts SDK ABI0 or9 without
rewriting binary bytes. A compiler/link/structural error stops the build.

Offline regressions (local loopback HTTP only):

```sh
bash relay-quality-test.sh
node tests/input/controller.cjs
python tools/verify_ps5_elf.py payload/build/pbs-relay-quality.elf
```

The packaging helper checks the upstream commit, expected change set, reviewed ELF
hash, combined patch application against a fresh pinned source export, and every
archive member's bytes. It explicitly includes the added profile_controls.h and
quality build/test helpers. The .tgz extension preserves compressed bytes in AAPT.

The native quality ELF has passed build/structural/local protocol tests and has
not yet been deployed to the PS5. The preceding native Create revision was deployed
separately. Real quality, bitrate and stress outcomes must be recorded from the
actual decoder and network; hardwareValidated remains false in this manifest.
