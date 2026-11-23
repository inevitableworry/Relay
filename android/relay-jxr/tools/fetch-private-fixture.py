#!/usr/bin/env python3
"""Read one owned PS5 capture over FTP into an ignored local test directory."""
import ftplib
from pathlib import Path

destination = Path(__file__).resolve().parents[1] / "private-fixtures" / "ps5-thumbnail.jxr"
remote = "/user/av_contents/thumbnails/photo/NPXS40087/PPSA09790/011/20261118_144939_00561523.jxr.jxr"
with ftplib.FTP() as ftp:
    ftp.connect("127.0.0.1", 2121, timeout=10)
    ftp.login("anonymous", "relay-preview-test")
    ftp.voidcmd("TYPE I")
    expected = ftp.size(remote)
    if expected is None or not 16 <= expected <= 4 * 1024 * 1024:
        raise RuntimeError("Unexpected capture size")
    chunks = []
    received = 0
    def consume(data):
        global received
        received += len(data)
        if received > expected:
            raise RuntimeError("Capture grew during read")
        chunks.append(data)
    ftp.retrbinary("RETR " + remote, consume)
    if received != expected or ftp.size(remote) != expected:
        raise RuntimeError("Capture changed during read")
destination.parent.mkdir(parents=True, exist_ok=True)
payload = b"".join(chunks)
destination.write_bytes(payload)
print(f"Saved private fixture: {len(payload)} bytes, signature {payload[:4].hex()}")
