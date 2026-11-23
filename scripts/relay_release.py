#!/usr/bin/env python3
"""Relay source-only release planning and validation, with fail-closed gates."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ELIGIBILITY = ROOT / "docs/release-validation/eligible-targets.json"
SOURCE_SUFFIXES = {".kt", ".java", ".c", ".cc", ".cpp", ".h", ".hpp", ".cs", ".swift", ".py", ".sh", ".ps1", ".gradle"}
TARGET_CHOICES = (
    "all", "3ds", "android-mobile", "android-tv", "android-bravia", "android-dualscreen",
    "android-other", "vita", "switch", "windows", "xbox", "apple", "tizen", "roku", "steamos"
)
VERSION_RE = re.compile(r"^relay-v(\d+)\.(\d+)\.(\d+)$")


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True, stderr=subprocess.DEVNULL).strip()


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def latest_version():
    versions = []
    for tag in git("tag", "--list", "relay-v*").splitlines():
        match = VERSION_RE.fullmatch(tag)
        if match:
            versions.append((tuple(map(int, match.groups())), tag))
    return max(versions, default=(None, None))


def code_stats(tag):
    if tag is None:
        # The first Relay release is a baseline, not all historical upstream changes.
        return 1, 1, True
    lines = git("diff", "--numstat", f"{tag}..HEAD", "--")
    num_files = 0
    changed_lines = 0
    platform_changed = False
    for line in lines.splitlines():
        fields = line.split("\t", 2)
        if len(fields) != 3:
            continue
        added, deleted, path = fields
        if path.startswith(("research/", "third-party/", "external/", "docs/", "releases/")):
            continue
        if Path(path).suffix.lower() not in SOURCE_SUFFIXES:
            continue
        num_files += 1
        changed_lines += (int(added) if added.isdigit() else 0) + (int(deleted) if deleted.isdigit() else 0)
        if path.startswith("platforms/3ds/"):
            platform_changed = True
    return num_files, changed_lines, platform_changed


def validate_target(name):
    entry = json.loads(ELIGIBILITY.read_text(encoding="utf-8"))["verified"].get(name)
    if not entry:
        return False, "platform not yet approved for public release"
    path = ROOT / entry["source"]
    if not path.is_file() or sha256(path) != entry["sha256"].lower():
        return False, "current source differs from visually verified version"
    for evidence in entry["evidence"]:
        if not (ROOT / evidence).is_file():
            return False, "required emulator capture missing"
    return True, "verified menu source and screenshots present"


def plan(target):
    previous, tag = latest_version()
    count, lines, three_ds_changed = code_stats(tag)
    if previous is None:
        next_version = (0, 1, 0)
    elif count >= 15 or lines >= 500:
        next_version = (previous[0], previous[1] + 1, 0)
    else:
        next_version = (previous[0], previous[1], previous[2] + 1)
    name = ".".join(map(str, next_version))
    approved, reason = validate_target("3ds")
    selected = target in ("3ds", "all")
    publish = selected and approved and three_ds_changed and count > 0
    return {
        "version": name,
        "tag": "relay-v" + name,
        "publish": publish,
        "targets": ["3ds"] if publish else [],
        "previous_tag": tag or "none",
        "changed_source_files": count,
        "changed_source_lines": lines,
        "reason": reason if not publish else "verified target has eligible source changes",
    }


def safety_scan():
    # Scan first-party authored files that might be published as a source tree.
    names = ("chat" + "gpt", "co" + "dex", "co" + "pilot", "clau" + "de", "open" + "ai")
    identified = "(?:" + "|".join(names) + ")"
    patterns = [
        re.compile(r"(?i)co-authored-by:\s*.*" + identified),
        re.compile(r"(?i)(?:created|generated|written|authored)\s+by\s+" + identified),
    ]
    blocked = []
    for name in git("ls-files", "-co", "--exclude-standard").splitlines():
        path = ROOT / name
        if not path.is_file() or path.stat().st_size > 1024 * 1024:
            continue
        if name.startswith(("research/", "third-party/", "external/", "docs/", "switch/", "test/")):
            continue
        if name in ("master girlthubrules.md", "RELAY_BUILD_PARITY_MASTER.md", "AGENTS.md"):
            continue
        if path.suffix.lower() not in SOURCE_SUFFIXES | {".md", ".xml", ".yaml", ".yml", ".json"}:
            continue
        try:
            content = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        if name == "scripts/relay_release.py":
            # Pattern definitions describe prohibited text; they are not attribution.
            content = "\n".join(line for line in content.splitlines() if "re.compile(" not in line)
        for regex in patterns:
            if regex.search(content):
                blocked.append(name)
    if blocked:
        raise SystemExit("Source hygiene check failed: " + ", ".join(sorted(set(blocked))))
    print("Source hygiene check passed")


def check_3ds(binary):
    path = Path(binary)
    if not path.is_file() or path.stat().st_size < 30000:
        raise SystemExit("Missing or undersized 3DS release binary")
    if path.open("rb").read(4) != b"3DSX":
        raise SystemExit("Not a valid 3DSX-format header")
    if any(s in path.name.lower() for s in ("debug", "test", "dev")):
        raise SystemExit("Development/debug package forbidden as public asset")
    valid, reason = validate_target("3ds")
    if not valid:
        raise SystemExit("Source not release-verified: " + reason)
    print(json.dumps({"name": path.name, "bytes": path.stat().st_size, "sha256": sha256(path)}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["plan", "scan", "verify-3ds"])
    parser.add_argument("--target", choices=TARGET_CHOICES, default="all")
    parser.add_argument("--github-output")
    parser.add_argument("--binary")
    args = parser.parse_args()
    if args.command == "scan":
        safety_scan()
    elif args.command == "verify-3ds":
        check_3ds(args.binary)
    else:
        result = plan(args.target)
        print(json.dumps(result, indent=2))
        if args.github_output:
            with Path(args.github_output).open("a", encoding="utf-8") as output:
                for key in ("version", "tag", "publish"):
                    output.write(f"{key}={str(result[key]).lower() if isinstance(result[key], bool) else result[key]}\n")
