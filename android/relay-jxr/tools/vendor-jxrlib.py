#!/usr/bin/env python3
"""Reproduce the checked-in Microsoft JPEG XR sources from a fixed public mirror."""
import hashlib
import io
import json
from pathlib import Path
import urllib.request
import zipfile

COMMIT = "1ef17dd8b7b6e958241914c8911d942a43837233"
URL = f"https://codeload.github.com/4creators/jxrlib/zip/{COMMIT}"
ROOT = Path(__file__).resolve().parents[3] / "third-party" / "jxrlib"
archive = urllib.request.urlopen(URL, timeout=60).read()
files = {}
with zipfile.ZipFile(io.BytesIO(archive)) as source:
    for member in source.infolist():
        parts = Path(member.filename).parts[1:]
        if not parts or member.is_dir():
            continue
        relative = Path(*parts)
        if not (parts[0] in ("common", "image", "jxrgluelib") or
                str(relative) in ("LICENSE", "README.md", "Makefile")):
            continue
        if relative.suffix not in (".c", ".h") and str(relative) not in ("LICENSE", "README.md", "Makefile"):
            continue
        content = source.read(member)
        destination = ROOT / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(content)
        files[relative.as_posix()] = hashlib.sha256(content).hexdigest()
ROOT.mkdir(parents=True, exist_ok=True)
(ROOT / "SOURCE.json").write_text(json.dumps({
    "repository": "https://github.com/4creators/jxrlib",
    "commit": COMMIT,
    "archiveUrl": URL,
    "archiveSha256": hashlib.sha256(archive).hexdigest(),
    "license": "BSD-2-Clause (Microsoft Corp.)",
    "files": files,
}, indent=2) + "\n", encoding="utf-8")
print(f"Vendored {len(files)} pinned files into {ROOT}")
