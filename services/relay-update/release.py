"""Relay release signing and publication. Signing needs cryptography >= 41.

Canonical JSON payloads are Ed25519 signed before publication. The private key
must live outside this repository and must never be copied into the web root.
"""

from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey

BASE_URL = os.environ.get("RELAY_UPDATE_PUBLIC_URL", "https://updates.example.invalid")
DEVELOPMENT_URL = os.environ.get("RELAY_UPDATE_DEVELOPMENT_URL", "https://staging-updates.example.invalid")
SCHEMA = "relay-update-v1"
TARGETS = {
    "android-mobile": ".apk",
    "android-tv": ".apk",
    "android-shield": ".apk",
    "android-google-tv": ".apk",
    "android-retroid": ".apk",
    "android-razer-edge": ".apk",
    "android-ayn-thor": ".apk",
    "vita": ".vpk",
    "switch1": ".nsp",
    "windows11": (".zip", ".exe", ".msix"),
    "rog-ally": (".zip", ".exe", ".msix"),
    "steam-deck": (".AppImage", ".flatpak", ".zip"),
    "meta-quest": ".apk",
    "xbox-series": ".appx",
    "xbox360": ".xex",
    "samsung-tizen": ".wgt",
    "roku": ".zip",
    "tvos": ".ipa",
    "ios": ".ipa",
}
CHANNELS = {"stable", "beta", "nightly"}
ANDROID_PACKAGES = {
    "android-mobile": ("com.portalstyle.remoteplay", 24),
    "android-tv": ("com.portalstyle.remoteplay.tv", 24),
    "android-shield": ("com.portalstyle.remoteplay.shield", 24),
    "android-google-tv": ("com.portalstyle.remoteplay.googletv", 24),
    "android-retroid": ("com.portalstyle.remoteplay.retroid", 24),
    "android-razer-edge": ("com.portalstyle.remoteplay.razeredge", 24),
    "android-ayn-thor": ("com.portalstyle.remoteplay.aynthor", 33),
    "meta-quest": ("com.portalstyle.remoteplay.quest", 29),
}
SLUG = re.compile(r"^[a-z0-9][a-z0-9-]{0,62}$")
ARTIFACT_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,150}$")


def canonical_bytes(value: dict) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False).encode("utf-8")


def key_id(public_key: Ed25519PublicKey) -> str:
    raw = public_key.public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    return hashlib.sha256(raw).hexdigest()[:16]


def keygen(private_path: Path, public_path: Path) -> None:
    if private_path.exists() or public_path.exists():
        raise ValueError("Refusing to replace an existing key. Rotate keys via a separate audited procedure.")
    if private_path.resolve().is_relative_to(public_path.parent.resolve()):
        raise ValueError("The private key must be outside the public release directory")
    private_path.parent.mkdir(parents=True, exist_ok=True)
    public_path.parent.mkdir(parents=True, exist_ok=True)
    key = Ed25519PrivateKey.generate()
    private_bytes = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    public_bytes = key.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)
    fd = os.open(private_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "wb") as out:
            out.write(private_bytes)
        os.chmod(private_path, 0o600)
        with public_path.open("xb") as out:
            out.write(public_bytes)
    except Exception:
        private_path.unlink(missing_ok=True)
        public_path.unlink(missing_ok=True)
        raise
    print(f"Public key ID: {key_id(key.public_key())}. Keep the private key offline and embed the public key in clients.")


def load_public(path: Path) -> Ed25519PublicKey:
    pub = serialization.load_pem_public_key(path.read_bytes())
    if not isinstance(pub, Ed25519PublicKey):
        raise ValueError("Expected an Ed25519 public key")
    return pub


def load_private(path: Path) -> Ed25519PrivateKey:
    key = serialization.load_pem_private_key(path.read_bytes(), password=None)
    if not isinstance(key, Ed25519PrivateKey):
        raise ValueError("Expected an Ed25519 private key")
    return key


def verify_manifest(document: dict, public_key: Ed25519PublicKey, target: str | None = None, channel: str | None = None) -> dict:
    if not isinstance(document, dict) or set(document) != {"payload", "signature"}:
        raise ValueError("Invalid manifest envelope")
    payload, signature = document["payload"], document["signature"]
    if not isinstance(payload, dict) or not isinstance(signature, dict):
        raise ValueError("Invalid manifest structure")
    if set(signature) != {"algorithm", "key_id", "value"} or signature["algorithm"] != "Ed25519":
        raise ValueError("Unsupported signature format")
    if signature["key_id"] != key_id(public_key):
        raise ValueError("Manifest signed by a different key")
    try:
        signature_bytes = base64.b64decode(signature["value"], validate=True)
        public_key.verify(signature_bytes, canonical_bytes(payload))
    except (InvalidSignature, ValueError, TypeError) as exc:
        raise ValueError("Invalid manifest signature") from exc
    if payload.get("schema") != SCHEMA or payload.get("app") != "relay":
        raise ValueError("Unexpected release schema")
    actual_target, actual_channel = payload.get("target"), payload.get("channel")
    if actual_target not in TARGETS or actual_channel not in CHANNELS:
        raise ValueError("Unknown target or channel")
    if target is not None and actual_target != target:
        raise ValueError("Target mismatch")
    if channel is not None and actual_channel != channel:
        raise ValueError("Channel mismatch")
    if type(payload.get("version_code")) is not int or payload["version_code"] < 1:
        raise ValueError("Invalid version code")
    a = payload.get("artifact")
    if not isinstance(a, dict) or not isinstance(a.get("name"), str) or not ARTIFACT_NAME.fullmatch(a["name"]):
        raise ValueError("Invalid artifact name")
    if type(a.get("size")) is not int or a["size"] < 1 or not re.fullmatch(r"[0-9a-f]{64}", str(a.get("sha256"))):
        raise ValueError("Invalid artifact digest or size")
    known_urls = {f"{base}/v1/{actual_target}/{actual_channel}/artifacts/{a['name']}"
                  for base in (BASE_URL, DEVELOPMENT_URL)}
    if a.get("url") not in known_urls:
        raise ValueError("Unexpected artifact URL")
    if actual_channel == "stable" and a["url"].startswith(DEVELOPMENT_URL + "/"):
        raise ValueError("Stable releases must use the canonical hostname")
    return payload


def sha256_and_size(path: Path) -> tuple[str, int]:
    sha = hashlib.sha256()
    size = 0
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            sha.update(chunk)
            size += len(chunk)
    return sha.hexdigest(), size


def publish(state: Path, private_path: Path, *, target: str, channel: str, source: Path,
            version: str, version_code: int, notes: str, package_id: str | None = None,
            minimum_os: str | None = None, base_url: str = BASE_URL) -> Path:
    if target not in TARGETS or channel not in CHANNELS:
        raise ValueError("Unknown target/channel; see TARGETS and CHANNELS")
    if not source.is_file() or not ARTIFACT_NAME.fullmatch(source.name):
        raise ValueError("Artifact must be a regular file with a simple filename")
    suffixes = TARGETS[target]
    if not source.name.endswith((suffixes,) if isinstance(suffixes, str) else suffixes):
        raise ValueError("Artifact extension does not match target platform")
    if type(version_code) is not int or version_code < 1 or not re.fullmatch(r"[0-9A-Za-z._+-]{1,64}", version):
        raise ValueError("Invalid version/version code")
    if base_url not in {BASE_URL, DEVELOPMENT_URL}:
        raise ValueError("Unrecognized update host")
    if channel == "stable" and base_url != BASE_URL:
        raise ValueError("Stable releases must use the canonical hostname")
    if target in ANDROID_PACKAGES:
        expected_id, min_sdk = ANDROID_PACKAGES[target]
        if package_id != expected_id:
            raise ValueError(f"Android package ID mismatch: expected {expected_id}")
        if not minimum_os or not minimum_os.isdigit() or int(minimum_os) < min_sdk:
            raise ValueError(f"Android minimum_os must match supported SDK floor of at least {min_sdk}")
    key = load_private(private_path)
    public_key = load_public(state / "public-key.pem")
    if key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw) != public_key.public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw):
        raise ValueError("Private signing key does not match deployed public key")
    directory = state / "releases" / target / channel
    old_manifest = directory / "manifest.json"
    if old_manifest.exists():
        old = verify_manifest(json.loads(old_manifest.read_text(encoding="utf-8")), public_key, target, channel)
        if version_code <= old["version_code"]:
            raise ValueError(f"Refusing rollback/replay: version_code {version_code} <= published {old['version_code']}")
    source_hash, source_size = sha256_and_size(source)
    if source_size == 0:
        raise ValueError("Empty artifact")
    name = f"{source_hash[:16]}-{source.name}"
    if not ARTIFACT_NAME.fullmatch(name):
        raise ValueError("Invalid derived filename")
    directory.mkdir(parents=True, exist_ok=True)
    artifacts = directory / "artifacts"
    artifacts.mkdir(exist_ok=True)
    destination = artifacts / name
    if destination.exists():
        if sha256_and_size(destination) != (source_hash, source_size):
            raise ValueError("Existing release artifact has different contents")
    else:
        fd, temp_name = tempfile.mkstemp(prefix=".relay-", dir=artifacts)
        try:
            with os.fdopen(fd, "wb") as out, source.open("rb") as inp:
                shutil.copyfileobj(inp, out, length=1024 * 1024)
            if sha256_and_size(Path(temp_name)) != (source_hash, source_size):
                raise ValueError("Artifact changed during copy")
            os.replace(temp_name, destination)
        finally:
            Path(temp_name).unlink(missing_ok=True)
    payload = {
        "schema": SCHEMA, "app": "relay", "target": target, "channel": channel,
        "version": version, "version_code": version_code,
        "published_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "notes": notes,
        "install": {"method": "android-package-installer" if target.startswith("android-") or target == "meta-quest" else "manual-platform-installer",
                    "package_id": package_id, "minimum_os": minimum_os},
        "artifact": {"name": name, "size": source_size, "sha256": source_hash,
                     "url": f"{base_url}/v1/{target}/{channel}/artifacts/{name}"},
    }
    document = {"payload": payload, "signature": {
        "algorithm": "Ed25519", "key_id": key_id(public_key),
        "value": base64.b64encode(key.sign(canonical_bytes(payload))).decode("ascii"),
    }}
    verify_manifest(document, public_key, target, channel)
    fd, temp_name = tempfile.mkstemp(prefix=".manifest-", dir=directory)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as out:
            json.dump(document, out, ensure_ascii=False, sort_keys=True, indent=2)
            out.write("\n")
        os.replace(temp_name, old_manifest)
    finally:
        Path(temp_name).unlink(missing_ok=True)
    return old_manifest


def main() -> int:
    parser = argparse.ArgumentParser(description="Publish independently signed Relay updates")
    parser.add_argument("--state", type=Path, default=Path(__file__).resolve().parent / "state")
    sub = parser.add_subparsers(dest="command", required=True)
    kg = sub.add_parser("keygen", help="Generate keys; private path MUST be outside the web root")
    kg.add_argument("--private", type=Path, required=True)
    pub = sub.add_parser("publish", help="Publish a signed release; never permits replay/rollback")
    pub.add_argument("--private", type=Path, required=True)
    pub.add_argument("--target", choices=sorted(TARGETS), required=True)
    pub.add_argument("--channel", choices=sorted(CHANNELS), required=True)
    pub.add_argument("--artifact", type=Path, required=True)
    pub.add_argument("--version", required=True)
    pub.add_argument("--version-code", required=True, type=int)
    pub.add_argument("--notes", default="")
    pub.add_argument("--package-id")
    pub.add_argument("--minimum-os")
    pub.add_argument("--base-url", choices=[BASE_URL, DEVELOPMENT_URL], default=BASE_URL,
                     help="Default is canonical nested host; first-level fallback allowed only for beta/nightly")
    ver = sub.add_parser("verify", help="Check the manifest's signature and target/channel")
    ver.add_argument("--manifest", type=Path, required=True)
    ver.add_argument("--target", choices=sorted(TARGETS))
    ver.add_argument("--channel", choices=sorted(CHANNELS))
    args = parser.parse_args()
    try:
        if args.command == "keygen":
            keygen(args.private, args.state / "public-key.pem")
        elif args.command == "publish":
            output = publish(args.state, args.private, target=args.target, channel=args.channel,
                             source=args.artifact, version=args.version, version_code=args.version_code,
                             notes=args.notes, package_id=args.package_id, minimum_os=args.minimum_os,
                             base_url=args.base_url)
            print(f"Signed manifest: {output}")
        else:
            payload = verify_manifest(json.loads(args.manifest.read_text(encoding="utf-8")),
                                      load_public(args.state / "public-key.pem"), args.target, args.channel)
            print(f"Valid Ed25519 signature: {payload['target']}/{payload['channel']} {payload['version']}")
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        parser.exit(2, f"Error: {exc}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
