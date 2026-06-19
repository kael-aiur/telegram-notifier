from datetime import datetime, timezone


def normalize_message(account_id, message):
    chat = getattr(message, "chat", None)
    sender = getattr(message, "from_user", None) or getattr(message, "sender_chat", None)
    received_at = getattr(message, "date", None)
    if isinstance(received_at, datetime):
        if received_at.tzinfo is None:
            received_at = received_at.replace(tzinfo=timezone.utc)
        received_at = received_at.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
    else:
        received_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")

    text = getattr(message, "text", None)
    if text is None:
        text = getattr(message, "caption", None)

    return {
        "accountId": int(account_id or 0),
        "chatId": int(_field(chat, "id", 0) or 0),
        "chatTitle": _chat_title(chat),
        "chatType": str(_field(chat, "type", "telegram") or "telegram"),
        "senderId": int(_field(sender, "id", 0) or 0),
        "senderName": _sender_name(sender),
        "senderUsername": str(_field(sender, "username", "") or ""),
        "receivedAt": received_at,
        "text": str(text or ""),
    }


def _field(value, name, default):
    return getattr(value, name, default) if value is not None else default


def _chat_title(chat):
    if chat is None:
        return ""
    return str(
        getattr(chat, "title", None)
        or getattr(chat, "first_name", None)
        or getattr(chat, "username", None)
        or ""
    )


def _sender_name(sender):
    if sender is None:
        return ""
    title = getattr(sender, "title", None)
    if title:
        return str(title)
    first = getattr(sender, "first_name", None) or ""
    last = getattr(sender, "last_name", None) or ""
    name = f"{first} {last}".strip()
    return name or str(getattr(sender, "username", None) or "")
