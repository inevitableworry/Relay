"""Loopback-only Relay signed update feed (fronted by a dedicated Cloudflare Tunnel)."""

from __future__ import annotations

import argparse
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import threading
from urllib.parse import urlsplit

from release import CHANNELS, TARGETS, load_public, sha256_and_size, verify_manifest

ROUTE = re.compile(r"^/v1/([a-z0-9-]+)/([a-z0-9-]+)/(manifest\.json|artifacts/[A-Za-z0-9][A-Za-z0-9._-]{0,150})$")
ANDROID_ALIAS = re.compile(r"^/api/v1/updates/android/(mobile|tv|shield|googleTv|retroid|razerEdge|aynThor|metaQuest)/(stable|beta|nightly)$")
ANDROID_MAP = {"mobile": "android-mobile", "tv": "android-tv", "shield": "android-shield",
               "googleTv": "android-google-tv", "retroid": "android-retroid", "razerEdge": "android-razer-edge",
               "aynThor": "android-ayn-thor", "metaQuest": "meta-quest"}
MAX_MANIFEST_BYTES = 32 * 1024


class FeedServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address: tuple[str, int], state: Path):
        if address[0] not in ("127.0.0.1", "::1", "localhost"):
            raise ValueError("The origin must listen only on loopback")
        self.state = state.resolve()
        super().__init__(address, FeedHandler)


class FeedHandler(BaseHTTPRequestHandler):
    server: FeedServer
    server_version = "RelayUpdates/1"

    def log_message(self, fmt: str, *args: object) -> None:
        # Do not log auth tokens or client-supplied query strings.
        print("relay-update:", fmt % args, flush=True)

    def do_HEAD(self) -> None:
        self.serve_request(head=True)

    def do_GET(self) -> None:
        self.serve_request(head=False)

    def do_POST(self) -> None:
        self.send_error(405)

    def _reply(self, status: int, data: bytes, content_type: str = "application/json", *, head: bool = False) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "public, max-age=60" if status == 200 else "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.end_headers()
        if not head:
            self.wfile.write(data)

    def _error(self, status: int, message: str, head: bool) -> None:
        self._reply(status, json.dumps({"error": message}).encode(), head=head)

    def serve_request(self, head: bool) -> None:
        # No redirects, listings, query-string controls or filesystem paths.
        url = urlsplit(self.path)
        if url.query or url.fragment or "%" in url.path or "\\" in url.path:
            return self._error(404, "not found", head)
        if url.path == "/healthz":
            return self._reply(200, b'{"service":"relay-updates","status":"alive"}', head=head)
        if url.path == "/readyz":
            ready = False
            public = self.server.state / "public-key.pem"
            if public.is_file():
                try:
                    signing_key = load_public(public)
                    for path in (self.server.state / "releases").glob("*/*/manifest.json"):
                        if path.stat().st_size <= MAX_MANIFEST_BYTES:
                            verify_manifest(json.loads(path.read_bytes()), signing_key)
                            ready = True
                            break
                except (ValueError, OSError, TypeError):
                    ready = False
            return self._reply(200 if ready else 503,
                               b'{"status":"release-available"}' if ready else b'{"status":"no-verified-release"}', head=head)
        match = ROUTE.fullmatch(url.path)
        alias = ANDROID_ALIAS.fullmatch(url.path)
        if match:
            target, channel, asset = match.groups()
        elif alias:
            flavor, channel = alias.groups()
            target, asset = ANDROID_MAP[flavor], "manifest.json"
        else:
            return self._error(404, "not found", head)
        if target not in TARGETS or channel not in CHANNELS:
            return self._error(404, "not found", head)
        public_path = self.server.state / "public-key.pem"
        manifest_path = self.server.state / "releases" / target / channel / "manifest.json"
        if not public_path.is_file() or not manifest_path.is_file():
            return self._error(503, "release not published", head)
        try:
            if manifest_path.stat().st_size > MAX_MANIFEST_BYTES:
                raise ValueError("Oversize manifest")
            raw = manifest_path.read_bytes()
            manifest = json.loads(raw)
            payload = verify_manifest(manifest, load_public(public_path), target, channel)
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            return self._error(503, "invalid release", head)
        if asset == "manifest.json":
            return self._reply(200, raw, head=head)
        name = asset[len("artifacts/"):]
        expected = payload["artifact"]
        if name != expected["name"]:
            return self._error(404, "not found", head)
        file_path = (manifest_path.parent / "artifacts" / name).resolve()
        allowed_dir = (manifest_path.parent / "artifacts").resolve()
        if file_path.parent != allowed_dir or not file_path.is_file():
            return self._error(404, "not found", head)
        try:
            digest, size = sha256_and_size(file_path)
        except OSError:
            return self._error(503, "artifact unreadable", head)
        if not hmac.compare_digest(digest, expected["sha256"]) or size != expected["size"]:
            return self._error(503, "artifact checksum mismatch", head)
        try:
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(size))
            self.send_header("Cache-Control", "public, max-age=31536000, immutable")
            self.send_header("ETag", '"' + digest + '"')
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            if not head:
                with file_path.open("rb") as fh:
                    for chunk in iter(lambda: fh.read(1024 * 1024), b""):
                        self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass


def main() -> None:
    parser = argparse.ArgumentParser(description="Relay read-only update feed")
    parser.add_argument("--port", type=int, default=18733)
    parser.add_argument("--state", type=Path, default=Path(__file__).resolve().parent / "state")
    args = parser.parse_args()
    with FeedServer(("127.0.0.1", args.port), args.state) as server:
        print(f"Relay signed update origin listening on 127.0.0.1:{server.server_port}", flush=True)
        try:
            server.serve_forever(poll_interval=0.25)
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
