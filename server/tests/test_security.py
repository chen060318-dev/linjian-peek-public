import json
import os
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))
import linjian_server


class PhoneBackendSecurityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        os.environ["LINJIAN_TOKEN"] = "test-phone-sync-token"
        os.environ["LINJIAN_DATA_DIR"] = cls.temp.name
        state = linjian_server.State()
        linjian_server.Handler.state = state
        cls.httpd = linjian_server.ThreadingHTTPServer(("127.0.0.1", 0), linjian_server.Handler)
        cls.base = f"http://127.0.0.1:{cls.httpd.server_address[1]}"
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        cls.thread.join(timeout=2)
        cls.temp.cleanup()

    def status(self, path, headers=None):
        try:
            with urlopen(Request(self.base + path, headers=headers or {}), timeout=3) as response:
                return response.status, json.loads(response.read().decode("utf-8"))
        except HTTPError as error:
            return error.code, json.loads(error.read().decode("utf-8"))

    def test_api_rejects_missing_token(self):
        status, body = self.status("/api/device/state")
        self.assertEqual(status, 403)
        self.assertEqual(body["error"], linjian_server.ERR_BAD_TOKEN)

    def test_query_token_cannot_bypass_header_auth(self):
        status, _ = self.status("/api/device/state?token=test-phone-sync-token")
        self.assertEqual(status, 403)

    def test_valid_header_token_can_read_state(self):
        status, body = self.status("/api/device/state", {"X-Auth-Token": "test-phone-sync-token"})
        self.assertEqual(status, 200)
        self.assertTrue(body["ok"])

    def test_new_api_route_is_protected_by_global_gate(self):
        status, _ = self.status("/api/known_apps")
        self.assertEqual(status, 403)


if __name__ == "__main__":
    unittest.main()

