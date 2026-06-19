import io
import json
import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2] / "main" / "resources" / "telegram-python-worker"
sys.path.insert(0, str(ROOT))

from telegram_worker.messages import normalize_message
from telegram_worker.protocol import emit_error, emit_status
from telegram_worker.proxy import ProxyConfigurationError, select_proxy, to_pyrogram_proxy
from telegram_worker.security import sanitize


class WorkerHelperTest(unittest.TestCase):
    def test_sanitize_masks_known_secret_values_and_key_values(self):
        value = sanitize("apiHash=abc proxy password=secret body", ["abc", "secret"])

        self.assertNotIn("abc", value)
        self.assertNotIn("secret", value)
        self.assertIn("******", value)

    def test_proxy_mapping_supports_socks5_and_http(self):
        socks = to_pyrogram_proxy({
            "protocol": "SOCKS5",
            "host": "127.0.0.1",
            "port": 1080,
            "username": "u",
            "password": "p",
        })
        http = to_pyrogram_proxy({"protocol": "HTTP", "host": "proxy.local", "port": 8080})

        self.assertEqual("socks5", socks["scheme"])
        self.assertEqual("http", http["scheme"])
        self.assertEqual("p", socks["password"])

    def test_proxy_mapping_rejects_https_with_clear_error(self):
        with self.assertRaises(ProxyConfigurationError) as raised:
            to_pyrogram_proxy({"protocol": "HTTPS", "host": "proxy.local", "port": 8443, "password": "secret"})

        self.assertNotIn("secret", str(raised.exception))

    def test_select_proxy_returns_first_proxy_id(self):
        active_proxy_id, proxy = select_proxy([
            {"id": 7, "protocol": "HTTP", "host": "proxy.local", "port": 8080}
        ])

        self.assertEqual(7, active_proxy_id)
        self.assertEqual("http", proxy["scheme"])

    def test_emit_status_and_error_are_json_lines_and_masked(self):
        original_stdout = sys.stdout
        try:
            sys.stdout = io.StringIO()
            emit_status(1, "WAIT_CODE", 2, "password=secret")
            emit_error(1, "apiHash=abc")
            lines = sys.stdout.getvalue().strip().splitlines()
        finally:
            sys.stdout = original_stdout

        self.assertEqual("status", json.loads(lines[0])["type"])
        self.assertEqual("error", json.loads(lines[1])["type"])
        self.assertNotIn("secret", lines[0])
        self.assertNotIn("abc", lines[1])

    def test_normalize_text_caption_and_empty_messages(self):
        chat = SimpleNamespace(id=100, title="Ops", type="supergroup")
        sender = SimpleNamespace(id=200, first_name="Ada", last_name="Lovelace", username="ada")
        date = datetime(2026, 6, 19, 1, 2, 3, tzinfo=timezone.utc)
        text_event = normalize_message(1, SimpleNamespace(chat=chat, from_user=sender, date=date, text="hello"))
        caption_event = normalize_message(1, SimpleNamespace(chat=chat, from_user=sender, date=date, text=None, caption="caption"))
        empty_event = normalize_message(1, SimpleNamespace(chat=None, from_user=None, date=date, text=None, caption=None))

        self.assertEqual("hello", text_event["text"])
        self.assertEqual("caption", caption_event["text"])
        self.assertEqual("", empty_event["text"])
        self.assertEqual("Ops", text_event["chatTitle"])
        self.assertEqual("Ada Lovelace", text_event["senderName"])
        self.assertEqual("2026-06-19T01:02:03Z", text_event["receivedAt"])


if __name__ == "__main__":
    unittest.main()
