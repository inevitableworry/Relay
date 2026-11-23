#!/usr/bin/env python3
"""Print JPEG XR format/dimension records without dumping personal capture metadata."""
from pathlib import Path
import struct
import sys

data = Path(sys.argv[1]).read_bytes()
offset = struct.unpack_from("<I", data, 4)[0]
count = struct.unpack_from("<H", data, offset)[0]
print(f"bytes={len(data)} magic={data[:4].hex()} ifd={offset} entries={count} guid={data[8:24].hex()}")
for index in range(count):
    tag, kind, number, value = struct.unpack_from("<HHII", data, offset + 2 + index * 12)
    if tag in (0xbc01, 0xbc02, 0xbc80, 0xbc81, 0xbcc0, 0xbcc1, 0xbcc2, 0xbcc3) or kind > 12:
        print(f"tag={tag:04x} type={kind} count={number} value={value}")
