#!/usr/bin/env python3
"""Relay cross-port resource guard.

Tests asset existence, copies of original menu cue audio and VPK/NSP packaging
inputs. It deliberately DOES NOT assert pixel-perfect visual or audio playback
parity. Those require dated screenshots and tests on a real device/emulator.
"""

from __future__ import annotations

import hashlib
from pathlib import Path
import struct
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
failures: list[str] = []
notes: list[str] = []


def required(path: str) -> Path | None:
    candidate = ROOT / path
    if not candidate.is_file():
        failures.append(f"MISSING: {path}")
        return None
    return candidate


def sha256(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(128 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


canonical = [
    "android/app/src/main/java/com/metallic/chiaki/main/PortalBackdropView.kt",
    "android/app/src/main/java/com/metallic/chiaki/main/PortalMainActivity.kt",
    "android/app/src/main/java/com/metallic/chiaki/main/PortalMotion.kt",
    "android/app/src/main/java/com/metallic/chiaki/main/PortalMenuAudio.kt",
    "android/app/src/main/res/layout/activity_portal_home.xml",
    "android/app/src/main/res/drawable-nodpi/portal_orb.png",
    "android/app/src/main/res/drawable-nodpi/relay_launcher.png",
]
for path in canonical:
    required(path)

for sound in (
    "menu_back.ogg",
    "menu_close.ogg",
    "menu_confirm.ogg",
    "menu_focus.ogg",
    "menu_open.ogg",
    "menu_reveal.ogg",
):
    android = required(f"android/app/src/main/assets/portal_audio/{sound}")
    for platform, path in (
        ("Vita", f"platforms/vita/assets/{sound}"),
        ("Switch", f"platforms/switch1/romfs/relay/audio/{sound}"),
    ):
        port = required(path)
        if android and port and sha256(android) != sha256(port):
            failures.append(f"ASSET DRIFT: {platform} {sound} != Android canonical cue")
        elif android and port:
            notes.append(f"IDENTICAL ASSET: {platform} {sound}")

for file in (
    "platforms/vita/source/main.c",
    "platforms/vita/assets/sphere.png",
    "platforms/vita/assets/halo.png",
    "platforms/vita/assets/cloud.png",
    "platforms/vita/assets/dot.png",
    "platforms/vita/assets/icon0.png",
    "platforms/switch1/source/main.cpp",
    "platforms/switch1/romfs/relay/nav/portal_orb.png",
    "RELAY_BUILD_PARITY_MASTER.md",
):
    required(file)


def png_dimensions(path: Path) -> tuple[int, int] | None:
    with path.open("rb") as stream:
        header = stream.read(24)
    if len(header) < 24 or header[:8] != b"\x89PNG\r\n\x1a\n":
        failures.append(f"INVALID PNG: {path.relative_to(ROOT)}")
        return None
    return struct.unpack(">II", header[16:24])


vita_icon = ROOT / "platforms/vita/assets/icon0.png"
if vita_icon.is_file():
    dim = png_dimensions(vita_icon)
    if dim != (128, 128):
        failures.append(f"Vita icon dimensions {dim}; require 128x128")

vita_package = ROOT / "platforms/vita/dist/Relay-PSVita.vpk"
if vita_package.is_file():
    with zipfile.ZipFile(vita_package) as archive:
        contents = set(archive.namelist())
    if "sce_sys/icon0.png" not in contents:
        failures.append("Vita VPK missing sce_sys/icon0.png (launcher icon)")
    elif vita_icon.is_file():
        notes.append("Vita packaged icon present")
else:
    notes.append("Vita VPK not built; package verification deferred")

print("Relay Android canonical resource check")
print(f"Resource mismatches/missing: {len(failures)}")
for message in failures:
    print("FAIL:", message)
for message in notes:
    print("INFO:", message)
print()
print("IMPORTANT: This checks source assets ONLY. It does NOT verify identical")
print("orb rendering, animations, Vita/Switch menu sound playback, streamed")
print("game audio, controller or touch behavior. See RELAY_BUILD_PARITY_MASTER.md")
print("for mandatory screenshots, audio capture and platform runtime gates.")
sys.exit(1 if failures else 0)
