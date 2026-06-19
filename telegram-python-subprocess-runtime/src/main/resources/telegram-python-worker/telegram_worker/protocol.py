import json
import sys
from datetime import datetime, timezone

from telegram_worker.security import sanitize


def read_commands():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        yield json.loads(line)


def emit(event):
    sys.stdout.write(json.dumps(event, ensure_ascii=False, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def emit_status(account_id, state, active_proxy_id=None, error_message=None):
    event = {
        "type": "status",
        "accountId": account_id,
        "state": state,
    }
    if active_proxy_id is not None:
        event["activeProxyId"] = active_proxy_id
    if error_message:
        event["errorMessage"] = sanitize(error_message)
    emit(event)


def emit_error(account_id, message):
    emit({
        "type": "error",
        "accountId": account_id,
        "message": sanitize(message),
    })


def emit_message(event):
    payload = {"type": "message"}
    payload.update(event)
    emit(payload)


def log(message):
    sys.stderr.write(sanitize(message) + "\n")
    sys.stderr.flush()


def utc_now():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
