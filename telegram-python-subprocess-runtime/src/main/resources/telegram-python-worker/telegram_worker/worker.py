import json
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from telegram_worker.messages import normalize_message
from telegram_worker.protocol import emit_error, emit_message, emit_reply, emit_status, emit_worker_state, log, read_commands
from telegram_worker.proxy import ProxyConfigurationError, select_proxy
from telegram_worker.security import safe_exception


@dataclass
class WorkerState:
    account_id: int = 0
    client: object = None
    phone_number: str = ""
    phone_code_hash: str = ""
    active_proxy_id: Optional[int] = None
    authorization_state: str = "LOGGED_OUT"
    data_dir: Path | None = None
    api_hash: str = ""


class TelegramWorker:
    def __init__(self):
        self.state = WorkerState()
        self.runtime_config = _runtime_config()
        self.current_input_id = None

    def run(self):
        try:
            emit_worker_state("ready")
            while True:
                try:
                    for command in read_commands():
                        if not self.handle(command):
                            return
                    return
                except KeyboardInterrupt:
                    log("Ignored KeyboardInterrupt while waiting for Java command")
        finally:
            self._disconnect()

    def handle(self, command):
        command_type = command.get("command") or command.get("type")
        self.current_input_id = command.get("_input_id")
        self.state.account_id = int(command.get("accountId") or self.state.account_id or 0)
        try:
            if command_type == "start":
                self.start(command)
            elif command_type == "submit_phone":
                self.submit_phone(command.get("phoneNumber") or "")
            elif command_type == "submit_code":
                self.submit_code(command.get("code") or "")
            elif command_type == "submit_password":
                self.submit_password(command.get("password") or "")
            elif command_type == "scan":
                self.scan(command)
            elif command_type == "fetch_unread":
                self.fetch_unread(command)
            elif command_type == "stop":
                self.stop()
                return False
            else:
                emit_error(self.state.account_id, f"Unsupported command type: {command_type}", self.current_input_id)
        except Exception as exc:
            self.fail(safe_exception(exc, self._secrets()))
        finally:
            self.current_input_id = None
        return True

    def start(self, command):
        self._disconnect()
        self.state.phone_number = str(command.get("phoneNumber") or "").strip()
        self.state.api_hash = str(command.get("apiHash") or "")
        data_dir = Path(str(command.get("dataDir") or "")).expanduser()
        if not data_dir:
            raise ValueError("dataDir is required")
        data_dir.mkdir(parents=True, exist_ok=True)
        if not data_dir.is_dir():
            raise ValueError(f"dataDir is not a directory: {data_dir}")
        self.state.data_dir = data_dir

        active_proxy_id, proxy = select_proxy(self.runtime_config.get("proxies") or [])
        self.state.active_proxy_id = active_proxy_id
        self.state.client = self._create_client(
            api_id=int(command.get("apiId") or 0),
            api_hash=self.state.api_hash,
            data_dir=data_dir,
            proxy=proxy,
        )
        self.state.client.connect()
        if self._is_authorized():
            self.ready()
            return
        if not self.state.phone_number:
            self.emit_status("WAIT_PHONE")
            return
        self._send_code(self.state.phone_number)

    def submit_phone(self, phone_number):
        self._require_client()
        self.state.phone_number = str(phone_number or "").strip()
        if not self.state.phone_number:
            self.emit_status("WAIT_PHONE", "phoneNumber is required")
            return
        self._send_code(self.state.phone_number)

    def submit_code(self, code):
        self._require_client()
        code = str(code or "").strip()
        if not code:
            self.emit_status("WAIT_CODE", "code is required")
            return
        if not self.state.phone_code_hash:
            self.emit_status("WAIT_CODE", "Submit phone number before code")
            return
        try:
            self.state.client.sign_in(self.state.phone_number, self.state.phone_code_hash, code)
            self.ready()
        except self._session_password_needed_error():
            self.emit_status("WAIT_PASSWORD")
        except self._bad_code_errors() as exc:
            self.emit_status("WAIT_CODE", safe_exception(exc, [code, *self._secrets()]))

    def submit_password(self, password):
        self._require_client()
        password = str(password or "")
        if not password:
            self.emit_status("WAIT_PASSWORD", "password is required")
            return
        try:
            self.state.client.check_password(password)
            self.ready()
        except self._bad_password_errors() as exc:
            self.emit_status("WAIT_PASSWORD", safe_exception(exc, [password, *self._secrets()]))

    def scan(self, command):
        self._require_client()
        chat_ids = _chat_ids(command.get("chatIds") or [])
        scanned = 0
        unread = 0
        emitted = 0
        target_ids = set(chat_ids)
        try:
            for dialog in self.state.client.get_dialogs():
                chat = getattr(dialog, "chat", None)
                chat_id = int(getattr(chat, "id", 0) or 0)
                if chat_id not in target_ids:
                    continue
                scanned += 1
                unread_count = int(getattr(dialog, "unread_messages_count", 0) or 0)
                log("scan dialog: chatId=%s unreadCount=%s" % (chat_id, unread_count))
                if unread_count <= 0:
                    continue
                unread += unread_count
                for message in self.state.client.get_chat_history(chat_id, limit=1):
                    emit_message(normalize_message(self.state.account_id, message), reply_input_id=self.current_input_id)
                    emitted += 1
                    break
        except Exception as exc:
            log("Failed to scan Telegram dialogs: " + safe_exception(exc, self._secrets()))
        log("scan completed: scanned=%d unread=%d emitted=%d" % (scanned, unread, emitted))
        emit_reply(self.current_input_id, True, result={"scanned": scanned, "unread": unread, "emitted": emitted})

    def fetch_unread(self, command):
        self._require_client()
        chat_id = int(command.get("chatId") or 0)
        if chat_id <= 0:
            emit_reply(self.current_input_id, True, result={"count": 0, "hasMore": False})
            return
        limit = _positive_int(command.get("limit"), 0)
        max_per_chat = _positive_int(command.get("maxPerChat"), 50)
        emitted = 0
        try:
            effective_limit = limit if limit > 0 else self._unread_count(chat_id)
            effective_limit = min(effective_limit, max_per_chat)
            if effective_limit > 0:
                for message in self.state.client.get_chat_history(chat_id, limit=effective_limit):
                    emit_message(normalize_message(self.state.account_id, message), reply_input_id=self.current_input_id)
                    emitted += 1
        except Exception as exc:
            message = safe_exception(exc, self._secrets())
            log("Failed to fetch unread messages: " + message)
            emit_error(self.state.account_id, message, self.current_input_id)
            return
        log("fetch_unread completed: chatId=%d count=%d" % (chat_id, emitted))
        emit_reply(self.current_input_id, True, result={"count": emitted, "hasMore": False})

    def _unread_count(self, chat_id):
        try:
            for dialog in self.state.client.get_dialogs():
                chat = getattr(dialog, "chat", None)
                if int(getattr(chat, "id", 0) or 0) == chat_id:
                    return int(getattr(dialog, "unread_messages_count", 0) or 0)
        except Exception as exc:
            log("Failed to read unread count for chat %s: %s" % (chat_id, safe_exception(exc, self._secrets())))
        return 0

    def ready(self):
        self._initialize_client()
        log("Telegram worker ready for command-driven unread scans")
        self.emit_status("READY")

    def stop(self):
        self._disconnect()
        self.emit_status("LOGGED_OUT")

    def fail(self, message):
        self.emit_status("ERROR", message)
        emit_error(self.state.account_id, message, self.current_input_id)

    def emit_status(self, state, error_message=None):
        self.state.authorization_state = state
        emit_status(self.state.account_id, state, self.state.active_proxy_id, error_message, self.current_input_id)

    def _send_code(self, phone_number):
        try:
            sent_code = self.state.client.send_code(phone_number)
            self.state.phone_code_hash = sent_code.phone_code_hash
            log("Telegram verification code requested: " + _sent_code_summary(sent_code))
            self.emit_status("WAIT_CODE")
        except self._phone_number_errors() as exc:
            self.emit_status("WAIT_PHONE", safe_exception(exc, [phone_number, *self._secrets()]))

    def _create_client(self, api_id, api_hash, data_dir, proxy):
        from pyrogram import Client

        if api_id <= 0:
            raise ValueError("apiId is required")
        if not api_hash:
            raise ValueError("apiHash is required")
        return Client(
            "telegram-account",
            api_id=api_id,
            api_hash=api_hash,
            workdir=str(data_dir),
            proxy=proxy,
            in_memory=False,
        )

    def _is_authorized(self):
        try:
            return self.state.client.get_me() is not None
        except Exception:
            return False

    def _register_message_handler(self):
        from pyrogram import filters
        from pyrogram.handlers import MessageHandler

        self.state.client.add_handler(MessageHandler(self._on_message, filters.all))

    def _initialize_client(self):
        self._require_client()
        if getattr(self.state.client, "is_initialized", False):
            return
        self.state.client.initialize()

    def _on_message(self, _client, message):
        try:
            emit_message(normalize_message(self.state.account_id, message))
        except Exception as exc:
            log("Failed to normalize Telegram message: " + safe_exception(exc, self._secrets()))

    def _disconnect(self):
        client = self.state.client
        self.state.client = None
        if client is None:
            return
        try:
            if getattr(client, "is_initialized", False):
                client.terminate()
            client.disconnect()
        except Exception as exc:
            log("Failed to disconnect Telegram client: " + safe_exception(exc, self._secrets()))

    def _require_client(self):
        if self.state.client is None:
            raise RuntimeError("Telegram client is not started")

    def _secrets(self):
        return [self.state.api_hash]

    def _session_password_needed_error(self):
        from pyrogram.errors import SessionPasswordNeeded

        return SessionPasswordNeeded

    def _bad_code_errors(self):
        from pyrogram.errors import PhoneCodeEmpty, PhoneCodeExpired, PhoneCodeInvalid

        return (PhoneCodeEmpty, PhoneCodeExpired, PhoneCodeInvalid)

    def _bad_password_errors(self):
        from pyrogram.errors import PasswordHashInvalid

        return (PasswordHashInvalid,)

    def _phone_number_errors(self):
        from pyrogram.errors import PhoneNumberInvalid

        return (PhoneNumberInvalid, ProxyConfigurationError)


def main():
    TelegramWorker().run()


def _runtime_config():
    raw = os.environ.get("TELEGRAM_SESSION_CONFIG") or "{}"
    try:
        value = json.loads(raw)
        return value if isinstance(value, dict) else {}
    except Exception:
        return {}


def _chat_ids(value):
    if not isinstance(value, list):
        return []
    output = []
    for item in value:
        try:
            output.append(int(item))
        except Exception:
            continue
    return output


def _positive_int(value, default):
    try:
        parsed = int(value)
        return parsed if parsed > 0 else default
    except Exception:
        return default


def _sent_code_summary(sent_code):
    code_type = getattr(sent_code, "type", None)
    next_type = getattr(sent_code, "next_type", None)
    timeout = getattr(sent_code, "timeout", None)
    return "type=%s nextType=%s timeout=%s" % (
        code_type or "",
        next_type or "",
        "" if timeout is None else timeout,
    )
