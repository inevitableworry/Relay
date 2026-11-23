"""Structural NRO and asset-bitstream validation (not a runtime/visual parity test)."""
from pathlib import Path
import hashlib
import json
import sys

folder = Path(__file__).resolve().parent
root = folder.parents[1]
nro = folder / "relay-switch1.nro"
errors = []
if not nro.is_file():
    errors.append("NRO not built")
else:
    content = nro.read_bytes()
    if content[0x10:0x14] != b"NRO0": errors.append("Invalid NRO magic")
    if len(content) < 500000: errors.append("NRO unexpectedly small")
    for path in (b"portal_nav_remote.png", b"menu_focus.ogg", b"portal_home_loop.pcm", b"inter-variable.ttf"):
        if path not in content: errors.append("NRO missing RomFS name " + path.decode())
manifest = json.loads((folder / "romfs/relay/ASSET_PROVENANCE.json").read_text())
for source, digest in manifest.items():
    p = root / source
    if not p.is_file() or hashlib.sha256(p.read_bytes()).hexdigest() != digest:
        errors.append("Original asset changed: " + source)
    if "/assets/portal_audio/" in source:
        copy = folder / "romfs/relay/audio" / p.name
        if not copy.is_file() or copy.read_bytes() != p.read_bytes():
            errors.append("Unmodified audio missing: " + source)
    if "/assets/fonts/" in source:
        copy = folder / "romfs/relay/fonts" / p.name
        if not copy.is_file() or copy.read_bytes() != p.read_bytes():
            errors.append("Unmodified font missing: " + source)
print("NRO integrity:", "PASS" if not errors else "FAIL")
print("Verified source provenance:", len(manifest), "files")
if nro.exists():
    print("NRO size:", nro.stat().st_size)
    print("NRO SHA256:", hashlib.sha256(nro.read_bytes()).hexdigest())
for item in errors: print("ERROR:", item)
sys.exit(bool(errors))
