#!/usr/bin/env python3
"""Relay PS5 spectate host control-plane simulator (NOT a PS5 payload).

Only binds to 127.0.0.1. Performs real pair-code exchange with local Relay
Social API, keeps the device bearer token server-side, exposes a small
PS Portal-inspired web configuration UI. Video capture remains disabled.
"""
import argparse
import base64
import hmac
import io
import json
import os
from pathlib import Path
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent
WEB = ROOT / "web"
STATE = ROOT / ".local-state"
ALLOWED_SOCIAL = {
    "http://127.0.0.1:48175",
} | {u.strip() for u in os.getenv("RELAY_SOCIAL_ALLOWED_URLS", "").split(",") if u.strip()}


def request_json(base: str, path: str, body: dict, timeout: int = 8) -> dict:
    url = base.rstrip("/") + path
    request = Request(url, json.dumps(body).encode(), {
        "Content-Type": "application/json",
        "User-Agent": "Relay-Host-Prototype/0.1",
    }, method="POST")
    with urlopen(request, timeout=timeout) as response:
        return json.load(response)


def pairing_qr_data_uri(verification_url: str) -> str:
    """QR contains an expiring pairing code, never the device bearer token."""
    import qrcode
    png = io.BytesIO()
    qrcode.make(verification_url).save(png, format="PNG")
    return "data:image/png;base64," + base64.b64encode(png.getvalue()).decode("ascii")


class HostServer(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, address, social: str, pin: str):
        super().__init__(address, Handler)
        self.social = social
        self.pin = pin
        self.lock = threading.Lock()
        self.pair_code = ""
        self.poll_token = ""
        self.pair_expires = 0
        self.paired = (STATE / "device-token.json").is_file()
        self.pending = False
        self.privacy = {
            "streaming_enabled": False,
            "auto_profile_sync": False,
            "friends_can_request": False,
        }


class Handler(BaseHTTPRequestHandler):
    server: HostServer
    def log_message(self, fmt, *args):
        print("relay-host:", fmt % args)

    def headers_safe(self, code: int, mime: str, length: int):
        self.send_response(code)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(length))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy",
            "default-src 'none'; script-src 'self'; style-src 'self'; "
            "img-src 'self' data:; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'")
        self.end_headers()

    def response(self, code: int, body: dict):
        blob = json.dumps(body).encode()
        self.headers_safe(code, "application/json", len(blob))
        self.wfile.write(blob)

    def authorized(self) -> bool:
        return hmac.compare_digest(
            self.headers.get("X-Relay-Setup-Pin", ""),
            self.server.pin
        )

    def parse_body(self) -> dict:
        size = int(self.headers.get("Content-Length", "0"))
        if size < 1 or size > 8192:
            raise ValueError("Unexpected request body size")
        data = json.loads(self.rfile.read(size))
        if not isinstance(data, dict):
            raise ValueError("Expected JSON object")
        return data

    def do_GET(self):
        route = self.path.split("?", 1)[0]
        if route == "/healthz":
            return self.response(200, {"ok": True, "kind": "local-ps5-host-simulator"})
        if route == "/api/status":
            return self.response(200, {
                "kind": "simulator", "paired": self.server.paired,
                "streaming_available": False,
                "streaming_running": False,
                "ps5_capture_available": False,
                "status": "Pairing control-plane only; PS5 capture payload not installed",
                "social": self.server.social,
            })
        files = {"/": "index.html", "/index.html": "index.html",
                 "/styles.css": "styles.css", "/app.js": "app.js"}
        name = files.get(route)
        if name is None:
            return self.response(404, {"error": "not_found"})
        file = WEB / name
        if not file.is_file():
            return self.response(404, {"error": "not_found"})
        blob = file.read_bytes()
        mime = "text/html; charset=utf-8" if name.endswith(".html") else (
            "text/css; charset=utf-8" if name.endswith(".css") else
            "text/javascript; charset=utf-8")
        self.headers_safe(200, mime, len(blob))
        self.wfile.write(blob)

    def do_POST(self):
        if not self.authorized():
            return self.response(403, {"error": "wrong_setup_pin"})
        if self.headers.get("Origin", "") not in ("",
                "http://127.0.0.1:" + str(self.server.server_port)):
            return self.response(403, {"error": "wrong_origin"})
        try:
            request_body = self.parse_body()
            if self.path == "/api/pair/start":
                with self.server.lock:
                    if self.server.pending and time.time() < self.server.pair_expires:
                        url = self.server.social.rstrip("/") + "/pair?code=" + self.server.pair_code
                        return self.response(200, {
                            "code": self.server.pair_code,
                            "url": url, "qrDataUri": pairing_qr_data_uri(url),
                            "expiresAt": int(self.server.pair_expires * 1000),
                        })
                    result = request_json(self.server.social, "/api/pairing/start", {
                        "name": request_body.get("name", "My PS5"),
                        "platform": "ps5",
                    })
                    self.server.pair_code = str(result["code"])
                    self.server.poll_token = str(result["pollToken"])
                    self.server.pair_expires = time.time() + 600
                    self.server.pending = True
                url = self.server.social.rstrip("/") + "/pair?code=" + self.server.pair_code
                return self.response(201, {
                    "code": self.server.pair_code,
                    "url": url, "qrDataUri": pairing_qr_data_uri(url),
                    "expiresAt": int(self.server.pair_expires * 1000),
                })
            if self.path == "/api/pair/status":
                if not self.server.pending or not self.server.poll_token:
                    return self.response(409, {"error": "no_pending_pairing"})
                result = request_json(self.server.social, "/api/pairing/status",
                                      {"pollToken": self.server.poll_token})
                if result.get("status") == "paired":
                    secret = result.get("deviceToken")
                    if not isinstance(secret, str) or not secret:
                        return self.response(502, {"error": "missing_device_token"})
                    STATE.mkdir(parents=True, exist_ok=True)
                    dest = STATE / "device-token.json"
                    with dest.open("w", encoding="utf-8") as out:
                        json.dump({"deviceToken": secret,
                                   "deviceId": result.get("deviceId")}, out)
                    os.chmod(dest, 0o600)
                    self.server.poll_token = ""
                    self.server.pair_code = ""
                    self.server.pending = False
                    self.server.paired = True
                return self.response(200, {"status": result.get("status", "pending"),
                                           "paired": self.server.paired})
            if self.path == "/api/settings":
                privacy = request_body.get("privacy", {})
                if not isinstance(privacy, dict):
                    raise ValueError("Invalid privacy configuration")
                # Deliberate: don't allow a prototype to activate unsupported
                # PS5 video or controller capture regardless of UI payload.
                self.server.privacy["streaming_enabled"] = False
                self.server.privacy["auto_profile_sync"] = bool(privacy.get("auto_profile_sync", False))
                self.server.privacy["friends_can_request"] = bool(privacy.get("friends_can_request", False))
                return self.response(200, {"privacy": self.server.privacy,
                    "warning": "streaming remains disabled until actual PS5 payload exists"})
            return self.response(404, {"error": "not_found"})
        except (HTTPError, URLError, ValueError, KeyError, json.JSONDecodeError) as exc:
            self.response(502, {"error": "operation_failed",
                                "message": str(exc)[:140]})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=48176)
    parser.add_argument("--social", default="http://127.0.0.1:48175")
    args = parser.parse_args()
    if args.social not in ALLOWED_SOCIAL:
        parser.error("Social origin must be one of the configured trusted hosts")
    pin = os.environ.get("RELAY_HOST_SETUP_PIN") or str(secrets.randbelow(900000)+100000)
    server = HostServer(("127.0.0.1", args.port), args.social, pin)
    print("Relay PS5 host simulator: http://127.0.0.1:" + str(server.server_port))
    print("Setup PIN for this local session:", pin)
    print("Simulator only: NO PS5 capture, no public network listener.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
