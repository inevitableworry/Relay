#!/usr/bin/env python3
"""Steam Deck Relay launcher: Portal UI and installed native stream backend.

This is a secure local-only launch adapter. It does not falsely claim that
the current Xbox/Windows web UI contains a Linux PS5 video decoder.
"""
from __future__ import annotations

import argparse
import functools
import hashlib
import http.server
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import threading
import time
import urllib.parse
import webbrowser

ROOT = Path(__file__).resolve().parent
WEB = ROOT / "wwwroot"
CANDIDATES = ("chiaki4deck", "chiaki-ng", "chiaki")


def find_backend() -> str | None:
    override = os.environ.get("RELAY_STEAMDECK_BACKEND")
    if override:
        chosen = Path(override).expanduser().resolve()
        return str(chosen) if chosen.is_file() and os.access(chosen, os.X_OK) else None
    return next((binary for name in CANDIDATES if (binary := shutil.which(name))), None)


class Handler(http.server.SimpleHTTPRequestHandler):
    secret = ""
    last_launch = 0.0
    last_lock = threading.Lock()

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(WEB), **kwargs)

    def send_json(self, code: int, obj: dict) -> None:
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self) -> None:
        path = urllib.parse.urlsplit(self.path).path
        if path == "/api/status":
            backend = find_backend()
            self.send_json(200, {
                "status": "ready" if backend else "backend_not_installed",
                "feature": "Steam Deck Portal launcher",
                "source": "installed native Chiaki-compatible client",
                "backend_available": bool(backend),
                "native_streaming_embedded": False,
                "token": self.secret,
            })
            return
        if path == "/healthz":
            self.send_json(200, {"ok": True, "port": "steamdeck"})
            return
        if path == "/":
            self.path = "/default.html"
        return super().do_GET()

    def do_POST(self) -> None:
        if urllib.parse.urlsplit(self.path).path != "/api/connect":
            return self.send_json(404, {"error": "not_found"})
        if self.headers.get("X-Relay-Local-Token") != self.secret:
            return self.send_json(403, {"error": "bad_session_token"})
        origin = self.headers.get("Origin", "")
        if origin and not origin.startswith(f"http://127.0.0.1:{self.server.server_port}"):
            return self.send_json(403, {"error": "unexpected_origin"})
        if int(self.headers.get("Content-Length", "0")) > 1024:
            return self.send_json(413, {"error": "body_too_large"})
        backend = find_backend()
        if backend is None:
            return self.send_json(503, {"error": "native_backend_missing",
                "message": "Install Chiaki-ng/Chiaki4deck or set RELAY_STEAMDECK_BACKEND."})
        with self.last_lock:
            now = time.monotonic()
            if now - self.last_launch < 2.5:
                return self.send_json(429, {"error": "launch_rate_limited"})
            self.__class__.last_launch = now
        try:
            subprocess.Popen([backend], cwd=str(Path.home()),
                             stdin=subprocess.DEVNULL, close_fds=True)
        except OSError as exc:
            return self.send_json(500, {"error": "launch_failed",
                "message": str(exc)[:180]})
        self.send_json(200, {"launched": True, "backend": Path(backend).name,
                             "streaming_embedded_in_relay": False})

    def log_message(self, fmt: str, *args) -> None:
        print("[relay-steamdeck] " + fmt % args, file=sys.stderr)


def run() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="Verify package without opening browser")
    parser.add_argument("--port", type=int, default=0)
    args = parser.parse_args()
    essentials = [WEB / n for n in (
        "default.html", "css/relay.css", "js/relay.js",
        "js/steamdeck-bridge.js", "Assets/portal_nav_remote.png")]
    missing = [str(f) for f in essentials if not f.is_file()]
    if missing:
        print("Missing web assets: " + ", ".join(missing), file=sys.stderr)
        return 1
    if args.check:
        print("Steam Deck package assets OK. Native client available:", bool(find_backend()))
        return 0
    Handler.secret = secrets.token_urlsafe(32)
    server = http.server.ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    url = f"http://127.0.0.1:{server.server_port}/"
    print("Relay Steam Deck local UI:", url)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    browser = next((shutil.which(x) for x in ("chromium", "chromium-browser", "google-chrome")
                    if shutil.which(x)), None)
    if browser:
        proc = subprocess.Popen([browser, f"--app={url}", "--start-fullscreen",
                                 "--no-first-run"], stdin=subprocess.DEVNULL)
        proc.wait()
    else:
        webbrowser.open(url)
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            pass
    server.shutdown()
    return 0


if __name__ == "__main__":
    raise SystemExit(run())
