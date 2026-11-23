# Relay contributor and coding-agent instructions

Read **`RELAY_BUILD_PARITY_MASTER.md`** before working on any Relay feature or
platform. This file is the canonical build inventory, parity contract, status
matrix, acceptance checklist and change log.

## Required on every user change

1. Android Relay's PS Portal-style UI, animation, icons, menu audio and
   navigation are the authoritative **design and feature reference**.
2. When the user requests a change, propagate it to **every maintained Relay
   platform** wherever feasible. Record each unimplemented or blocked port in
   the master matrix with an accurate reason. Do not silently skip a port.
3. Keep each target's UI as visually and behaviorally close to Android as its
   native renderer permits; do not replace dimensional materials or the moving
   orb with flat placeholders.
4. After **every code/resource/build/UI/audio change**, update
   `RELAY_BUILD_PARITY_MASTER.md` and its dated Change Log before declaring
   completion. Pre-release build outputs must follow the cleanup and naming
   policy in `docs/RELAY_ARTIFACT_POLICY.md`; never delete unrelated data.
5. Run `python tools/check_relay_ui_parity.py` and
   `python platforms/switch1/verify_ui_parity.py`. Review any intentional
   Android baseline hash change and document the reason.
6. Capture the same UI state and animation times on Android and each changed
   port; run `python tools/compare_relay_ui_frames.py --android <reference>
   --target <capture> --out <comparison> --state home --time 3s`, inspect the
   comparison and record visual discrepancies honestly. A resource/hash check
   does **not** prove rendered parity.
7. Test **menu cues separately from game/stream audio**, and physical
   controller/touch mappings separately from PS5 stream input delivery.
8. A successful compile/packaging step never means the app launches or works
   on real hardware. Log artifact hashes, emulator/physical device, OS/firmware,
   frame screenshots, errors and pass/failed/blocked status.
9. Follow the master rule against unnecessary tool-authorship banners,
   credits, commit trailers, and generated metadata across all Relay builds.
   Preserve legal license notices, real contributor records, provenance, and
   functional service or API references. Audit before any GitHub publication.

Do not overwrite unrelated user data, other installed games, saved controller
profiles, emulator state, or project changes.
