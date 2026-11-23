"""Functional regression tests for signing, checksum, routing and replay."""

import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import urlopen

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from release import DEVELOPMENT_URL, keygen, load_public, publish, verify_manifest
from server import FeedServer


class ReleaseFeedTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.state = root / "state"
        self.key = root / "signing" / "private.pem"
        keygen(self.key, self.state / "public-key.pem")
        self.apk = root / "relay.apk"
        self.apk.write_bytes(b"relay test artifact\x00" * 512)
        self.server = FeedServer(("127.0.0.1", 0), self.state)
        self.addCleanup(self.server.server_close)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)

    def stop_server(self):
        self.server.shutdown()
        self.thread.join(timeout=5)

    def request(self, path: str, expected: int = 200):
        try:
            with urlopen(f"http://127.0.0.1:{self.server.server_port}{path}", timeout=3) as res:
                code, data = res.status, res.read()
        except HTTPError as err:
            try:
                code, data = err.code, err.read()
            finally:
                err.close()
        self.assertEqual(expected, code, data.decode("utf-8", errors="replace"))
        return data

    def publish(self, code: int = 38):
        return publish(self.state, self.key, target="android-mobile", channel="stable",
                       source=self.apk, version="0.3.24", version_code=code,
                       notes="Signed test release", package_id="com.portalstyle.remoteplay", minimum_os="24")

    def test_end_to_end_signed_manifest_and_artifact(self):
        manifest_path = self.publish()
        data = self.request("/v1/android-mobile/stable/manifest.json")
        self.assertEqual(json.loads(manifest_path.read_bytes()), json.loads(data))
        self.assertEqual(data, self.request("/api/v1/updates/android/mobile/stable"))
        self.request("/readyz")
        payload = verify_manifest(json.loads(data), load_public(self.state / "public-key.pem"), "android-mobile", "stable")
        name = payload["artifact"]["name"]
        self.assertEqual(self.apk.read_bytes(), self.request(f"/v1/android-mobile/stable/artifacts/{name}"))
        self.request("/v1/android-mobile/stable/artifacts/unlisted.apk", 404)

    def test_rollback_replay_and_signing_key_mismatch(self):
        self.publish()
        with self.assertRaisesRegex(ValueError, "rollback/replay"):
            self.publish()
        with self.assertRaisesRegex(ValueError, "rollback/replay"):
            self.publish(37)
        other = Path(self.temp.name) / "other"
        keygen(other / "private" / "different.key", other / "public" / "public-key.pem")
        with self.assertRaisesRegex(ValueError, "does not match"):
            publish(self.state, other / "private" / "different.key", target="android-mobile", channel="beta",
                    source=self.apk, version="0.3.25", version_code=39, notes="",
                    package_id="com.portalstyle.remoteplay", minimum_os="24")

    def test_rejects_wrong_android_package_id_and_sdk(self):
        with self.assertRaisesRegex(ValueError, "package ID mismatch"):
            publish(self.state, self.key, target="android-mobile", channel="stable", source=self.apk,
                    version="0.3.24", version_code=38, notes="", package_id="com.fake.apk", minimum_os="24")
        with self.assertRaisesRegex(ValueError, "SDK floor"):
            publish(self.state, self.key, target="android-mobile", channel="stable", source=self.apk,
                    version="0.3.24", version_code=38, notes="", package_id="com.portalstyle.remoteplay", minimum_os="23")

    def test_explicit_beta_fallback_host_only(self):
        with self.assertRaisesRegex(ValueError, "Stable releases"):
            publish(self.state, self.key, target="android-mobile", channel="stable", source=self.apk,
                    version="0.3.24", version_code=38, notes="",
                    package_id="com.portalstyle.remoteplay", minimum_os="24", base_url=DEVELOPMENT_URL)
        manifest = publish(self.state, self.key, target="android-mobile", channel="beta", source=self.apk,
                           version="0.3.24", version_code=38, notes="Test only",
                           package_id="com.portalstyle.remoteplay", minimum_os="24", base_url=DEVELOPMENT_URL)
        document = json.loads(manifest.read_text())
        self.assertTrue(document["payload"]["artifact"]["url"].startswith(DEVELOPMENT_URL + "/"))
        verify_manifest(document, load_public(self.state / "public-key.pem"), "android-mobile", "beta")

    def test_tampered_metadata_and_artifact_are_rejected(self):
        manifest_path = self.publish()
        document = json.loads(manifest_path.read_text())
        name = document["payload"]["artifact"]["name"]
        document["payload"]["version_code"] = 999
        manifest_path.write_text(json.dumps(document))
        self.request("/v1/android-mobile/stable/manifest.json", 503)
        self.publish_manifest_restore(document, manifest_path)
        path = manifest_path.parent / "artifacts" / name
        path.write_bytes(b"tampered")
        self.request(f"/v1/android-mobile/stable/artifacts/{name}", 503)

    def publish_manifest_restore(self, document, manifest_path):
        document["payload"]["version_code"] = 38
        manifest_path.write_text(json.dumps(document))

    def test_rejects_unknown_routes_traversal_and_methods(self):
        self.request("/healthz")
        self.request("/readyz", 503)
        self.request("/v1/android-mobile/bad/manifest.json", 404)
        self.request("/api/v1/updates/android/aynThor/beta", 503)
        self.request("/api/v1/updates/android/metaQuest/beta", 503)
        self.request("/v1/android-mobile/stable/%2e%2e/manifest.json", 404)
        self.request("/v1/windows11/stable/manifest.json", 503)

    def test_keygen_never_overwrites(self):
        with self.assertRaisesRegex(ValueError, "Refusing to replace"):
            keygen(self.key, self.state / "public-key.pem")
        with self.assertRaisesRegex(ValueError, "outside"):
            keygen(self.state / "oops.key", self.state / "another-public.pem")


if __name__ == "__main__":
    unittest.main()
