import io
import json
import os
import sys
import time
import unittest
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2] / "main" / "resources" / "telegram-python-worker"
sys.path.insert(0, str(ROOT))

from telegram_worker.messages import normalize_message
from telegram_worker.protocol import emit_error, emit_log, emit_message, emit_reply, emit_status
from telegram_worker.proxy import ProxyConfigurationError, select_proxy, to_pyrogram_proxy
from telegram_worker.security import sanitize
from telegram_worker.worker import TelegramWorker


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

    def test_emit_reply_log_message_status_and_error_are_json_envelopes_and_masked(self):
        original_stdout = sys.stdout
        try:
            sys.stdout = io.StringIO()
            emit_reply("java-1", True, result={"value": "ok"})
            emit_log("password=secret")
            emit_message({"text": "hello"})
            emit_message({"text": "pulled"}, reply_input_id="java-fetch")
            emit_status(1, "WAIT_CODE", 2, "password=secret", reply_input_id="java-status")
            emit_error(1, "apiHash=abc", reply_input_id="java-error")
            lines = sys.stdout.getvalue().strip().splitlines()
        finally:
            sys.stdout = original_stdout

        envelopes = [json.loads(line) for line in lines]
        self.assertEqual("reply", envelopes[0]["type"])
        self.assertEqual("java-1", envelopes[0]["replyInputId"])
        self.assertEqual("log", envelopes[1]["type"])
        self.assertEqual("message", envelopes[2]["type"])
        self.assertNotIn("replyInputId", envelopes[2])
        self.assertEqual("java-fetch", envelopes[3]["replyInputId"])
        self.assertEqual("reply", envelopes[4]["type"])
        self.assertFalse(envelopes[4]["content"]["ok"])
        self.assertEqual("java-error", envelopes[5]["replyInputId"])
        self.assertNotIn("secret", "\n".join(lines))
        self.assertNotIn("abc", lines[5])

    def test_normalize_text_caption_and_empty_messages(self):
        chat = SimpleNamespace(id=100, title="Ops", type="supergroup")
        sender = SimpleNamespace(id=200, first_name="Ada", last_name="Lovelace", username="ada")
        date = datetime(2026, 6, 19, 1, 2, 3, tzinfo=timezone.utc)
        text_event = normalize_message(1, SimpleNamespace(id=10, chat=chat, from_user=sender, date=date, text="hello"))
        caption_event = normalize_message(1, SimpleNamespace(id=11, chat=chat, from_user=sender, date=date, text=None, caption="caption"))
        empty_event = normalize_message(1, SimpleNamespace(id=None, chat=None, from_user=None, date=date, text=None, caption=None))

        self.assertEqual(10, text_event["messageId"])
        self.assertEqual(11, caption_event["messageId"])
        self.assertEqual(0, empty_event["messageId"])
        self.assertEqual("hello", text_event["text"])
        self.assertEqual("caption", caption_event["text"])
        self.assertEqual("", empty_event["text"])
        self.assertEqual("Ops", text_event["chatTitle"])
        self.assertEqual("Ada Lovelace", text_event["senderName"])
        self.assertEqual("2026-06-19T01:02:03Z", text_event["receivedAt"])

    def test_scan_emits_unread_messages_for_requested_chats_and_reply(self):
        chat = SimpleNamespace(id=100, title="Ops", type="private")
        other_chat = SimpleNamespace(id=200, title="Other", type="private")
        sender = SimpleNamespace(id=300, first_name="Ada", last_name="", username="ada")
        date = datetime(2026, 6, 19, 1, 2, 3, tzinfo=timezone.utc)
        message = SimpleNamespace(id=7, chat=chat, from_user=sender, date=date, text="unread", caption=None)

        class FakeClient:
            def get_dialogs(self):
                return [
                    SimpleNamespace(chat=chat, unread_messages_count=1),
                    SimpleNamespace(chat=other_chat, unread_messages_count=1),
                ]

            def get_chat_history(self, chat_id, limit):
                if chat_id == 100 and limit == 1:
                    return [message]
                return [SimpleNamespace(id=8, chat=other_chat, from_user=sender, date=date, text="skip", caption=None)]

        worker = TelegramWorker()
        worker.state.account_id = 1
        worker.state.client = FakeClient()
        worker.current_input_id = "java-scan"

        original_stdout = sys.stdout
        try:
            sys.stdout = io.StringIO()
            worker.scan({"chatIds": [100], "maxMessagesPerChat": 20})
            lines = sys.stdout.getvalue().strip().splitlines()
        finally:
            sys.stdout = original_stdout

        envelopes = [json.loads(line) for line in lines]
        message_envelope = next(envelope for envelope in envelopes if envelope["type"] == "message")
        self.assertEqual("java-scan", message_envelope["replyInputId"])
        self.assertEqual(7, message_envelope["content"]["messageId"])
        self.assertEqual(100, message_envelope["content"]["chatId"])
        self.assertEqual("reply", envelopes[-1]["type"])
        self.assertEqual("java-scan", envelopes[-1]["replyInputId"])
        self.assertEqual(1, envelopes[-1]["content"]["result"]["scanned"])
        self.assertEqual(1, envelopes[-1]["content"]["result"]["emitted"])
        self.assertNotIn("skip", "\n".join(lines))

    def test_scan_replies_when_requested_chat_has_no_unread_messages(self):
        chat = SimpleNamespace(id=100, title="Ops", type="private")

        class FakeClient:
            def get_dialogs(self):
                return [SimpleNamespace(chat=chat, unread_messages_count=0)]

            def get_chat_history(self, chat_id, limit):
                raise AssertionError("history should not be fetched for read chat")

        worker = TelegramWorker()
        worker.state.account_id = 1
        worker.state.client = FakeClient()
        worker.current_input_id = "java-scan"

        original_stdout = sys.stdout
        try:
            sys.stdout = io.StringIO()
            worker.scan({"chatIds": [100], "maxMessagesPerChat": 20})
            lines = sys.stdout.getvalue().strip().splitlines()
        finally:
            sys.stdout = original_stdout

        envelopes = [json.loads(line) for line in lines]
        self.assertFalse(any(envelope["type"] == "message" for envelope in envelopes))
        reply = envelopes[-1]
        self.assertEqual("reply", reply["type"])
        self.assertEqual(0, reply["content"]["result"]["unread"])
        self.assertEqual(0, reply["content"]["result"]["emitted"])

    def test_normalize_naive_local_datetime_to_utc(self):
        if not hasattr(time, "tzset"):
            self.skipTest("tzset is not available on this platform")
        original_tz = os.environ.get("TZ")
        try:
            os.environ["TZ"] = "Asia/Shanghai"
            time.tzset()
            chat = SimpleNamespace(id=100, title="Ops", type="private")
            event = normalize_message(1, SimpleNamespace(id=10, chat=chat, from_user=None,
                                                         date=datetime(2026, 6, 19, 21, 46, 6),
                                                         text="hello", caption=None))
        finally:
            if original_tz is None:
                os.environ.pop("TZ", None)
            else:
                os.environ["TZ"] = original_tz
            time.tzset()

        self.assertEqual("2026-06-19T13:46:06Z", event["receivedAt"])

    def test_ready_initializes_client_without_live_message_handler(self):
        class FakeClient:
            def __init__(self):
                self.handlers = []
                self.is_initialized = False

            def add_handler(self, handler):
                self.handlers.append(handler)

            def initialize(self):
                self.is_initialized = True

        worker = TelegramWorker()
        client = FakeClient()
        worker.state.account_id = 1
        worker.state.client = client
        worker.current_input_id = "java-ready"
        worker._register_message_handler = lambda: client.add_handler("handler")

        original_stdout = sys.stdout
        try:
            sys.stdout = io.StringIO()
            worker.ready()
            lines = sys.stdout.getvalue().strip().splitlines()
        finally:
            sys.stdout = original_stdout

        envelopes = [json.loads(line) for line in lines]
        self.assertEqual([], client.handlers)
        self.assertTrue(client.is_initialized)
        self.assertEqual("log", envelopes[0]["type"])
        self.assertEqual("READY", envelopes[-1]["content"]["result"]["status"]["state"])
        self.assertEqual("java-ready", envelopes[-1]["replyInputId"])


if __name__ == "__main__":
    unittest.main()
