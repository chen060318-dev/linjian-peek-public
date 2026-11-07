import json
import os
import sys
import tempfile
import threading
import time
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

    def post_json(self, path, payload, headers=None):
        request_headers = {"Content-Type": "application/json", **(headers or {})}
        request = Request(self.base + path, data=json.dumps(payload).encode("utf-8"), headers=request_headers, method="POST")
        with urlopen(request, timeout=3) as response:
            return response.status, json.loads(response.read().decode("utf-8"))

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

    def test_expired_command_is_never_dispatched(self):
        auth = {"X-Auth-Token": "test-phone-sync-token"}
        _, queued = self.post_json("/api/command", {
            "device_id": "android-phone", "action": "screen_break_app", "app": "小红书",
            "package": "com.xingin.xhs", "duration_minutes": 1,
            "expires_at_ms": int(time.time() * 1000) - 1000
        }, auth)
        command_id = queued["command"]["id"]
        status, polled = self.status("/api/poll?device_id=android-phone", auth)
        self.assertEqual(status, 200)
        self.assertIsNone(polled["command"])
        _, command_status = self.status(f"/api/command/status?id={command_id}", auth)
        self.assertEqual(command_status["command"]["status"], "expired")

    def test_screenshot_upload_is_bound_to_its_command_id(self):
        auth = {"X-Auth-Token": "test-phone-sync-token"}
        _, queued = self.post_json("/api/command", {"device_id": "android-phone", "action": "peek", "ttl_seconds": 90}, auth)
        command_id = queued["command"]["id"]
        _, polled = self.status("/api/poll?device_id=android-phone", auth)
        self.assertEqual(polled["command"]["id"], command_id)
        image = b"\xff\xd8" + (b"test-image" * 20)
        upload_headers = {**auth, "Content-Type": "image/jpeg", "X-Command-Id": command_id}
        request = Request(self.base + "/api/screenshot", data=image, headers=upload_headers, method="POST")
        with urlopen(request, timeout=3) as response:
            uploaded = json.loads(response.read().decode("utf-8"))
        self.assertEqual(uploaded["command_id"], command_id)
        _, command_status = self.status(f"/api/command/status?id={command_id}", auth)
        self.assertEqual(command_status["command"]["status"], "completed")
        self.assertEqual(command_status["command"]["screenshot_filename"], uploaded["filename"])
        with urlopen(Request(self.base + f"/api/screenshot?id={command_id}", headers=auth), timeout=3) as response:
            self.assertEqual(response.read(), image)

    def test_received_command_is_not_redispatched_and_terminal_report_removes_it(self):
        auth = {"X-Auth-Token": "test-phone-sync-token"}
        _, queued = self.post_json("/api/command", {"device_id": "android-phone", "action": "get_screen_break_state"}, auth)
        command_id = queued["command"]["id"]
        _, first_poll = self.status("/api/poll?device_id=android-phone", auth)
        self.assertEqual(first_poll["command"]["id"], command_id)
        self.post_json("/api/device/report", {"command_id": command_id, "status": "received", "ok": True, "result": "phone_received_command"}, auth)
        _, second_poll = self.status("/api/poll?device_id=android-phone", auth)
        self.assertIsNone(second_poll["command"])
        self.post_json("/api/device/report", {"command_id": command_id, "status": "completed", "ok": True, "result": "{}"}, auth)
        _, command_status = self.status(f"/api/command/status?id={command_id}", auth)
        self.assertEqual(command_status["command"]["status"], "completed")


if __name__ == "__main__":
    unittest.main()

