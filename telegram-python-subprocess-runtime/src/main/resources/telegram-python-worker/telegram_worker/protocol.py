import json
import sys
from datetime import datetime, timezone
from itertools import count

from telegram_worker.security import sanitize

_id_counter = count(1)


def read_commands():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        envelope = json.loads(line)
        input_id = _require_text(envelope, "id")
        envelope_type = _require_text(envelope, "type")
        if envelope_type != "input":
            emit_reply(input_id, False, error={"code": "invalid_type", "message": "Unsupported input type: " + envelope_type})
            continue
        if "content" not in envelope:
            emit_reply(input_id, False, error={"code": "missing_content", "message": "Input content is required"})
            continue
        content = envelope["content"]
        if isinstance(content, dict):
            command = dict(content)
        else:
            command = {"value": content}
        command["_input_id"] = input_id
        yield command


def emit(event):
    sys.stdout.write(json.dumps(event, ensure_ascii=False, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def emit_reply(reply_input_id, ok=True, result=None, error=None, content=None):
    payload = {
        "id": _next_id("py-reply"),
        "type": "reply",
        "replyInputId": str(reply_input_id or ""),
        "content": content if content is not None else _reply_content(ok, result, error),
    }
    emit(payload)


def emit_log(message, level="INFO", reply_input_id=None):
    payload = {
        "id": _next_id("py-log"),
        "type": "log",
        "content": {
            "level": level,
            "message": sanitize(str(message or "")),
            "time": utc_now(),
        },
    }
    if reply_input_id:
        payload["replyInputId"] = str(reply_input_id)
    emit(payload)


def emit_worker_state(state):
    emit_log("worker_state=" + str(state or ""))


def emit_status(account_id, state, active_proxy_id=None, error_message=None, reply_input_id=None):
    status = {
        "accountId": account_id,
        "state": state,
    }
    if active_proxy_id is not None:
        status["activeProxyId"] = active_proxy_id
    if error_message:
        status["errorMessage"] = sanitize(error_message)
    if reply_input_id:
        error = None
        if error_message:
            error = {"code": "status_error", "message": sanitize(error_message)}
        emit_reply(reply_input_id, error is None, result={"status": status}, error=error)
    else:
        emit_log({"status": status})


def emit_error(account_id, message, reply_input_id=None):
    error = {
        "code": "worker_error",
        "message": sanitize(message),
        "accountId": account_id,
    }
    if reply_input_id:
        emit_reply(reply_input_id, False, error=error)
    else:
        emit_log(error, level="ERROR")


def emit_message(event, reply_input_id=None):
    payload = {
        "id": _next_id("py-msg"),
        "type": "message",
        "content": event,
    }
    if reply_input_id:
        payload["replyInputId"] = str(reply_input_id)
    emit(payload)


def log(message):
    emit_log(message)


def utc_now():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _next_id(prefix):
    return "%s-%s" % (prefix, next(_id_counter))


def _reply_content(ok, result, error):
    content = {"ok": bool(ok)}
    if result is not None:
        content["result"] = result
    if error is not None:
        content["error"] = _sanitize_value(error)
    return content


def _sanitize_value(value):
    if isinstance(value, dict):
        return {key: _sanitize_value(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_sanitize_value(item) for item in value]
    if isinstance(value, str):
        return sanitize(value)
    return value


def _require_text(envelope, field):
    value = envelope.get(field)
    if value is None or str(value).strip() == "":
        raise ValueError("Input envelope is missing " + field)
    return str(value)
