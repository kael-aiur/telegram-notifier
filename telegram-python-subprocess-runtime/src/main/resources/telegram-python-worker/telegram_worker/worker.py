from telegram_worker.protocol import emit_error, emit_status, read_commands


def main():
    account_id = 0
    for command in read_commands():
        account_id = command.get("accountId", account_id)
        command_type = command.get("type")
        if command_type == "start":
            phone_number = command.get("phoneNumber") or ""
            emit_status(account_id, "WAIT_CODE" if phone_number else "WAIT_PHONE",
                        _active_proxy_id(command.get("proxies") or []))
        elif command_type == "submit_phone":
            emit_status(account_id, "WAIT_CODE")
        elif command_type == "submit_code":
            emit_status(account_id, "READY")
        elif command_type == "submit_password":
            emit_status(account_id, "READY")
        elif command_type == "scan":
            emit_status(account_id, "READY")
        elif command_type == "stop":
            emit_status(account_id, "LOGGED_OUT")
            return
        else:
            emit_error(account_id, f"Unsupported command type: {command_type}")


def _active_proxy_id(proxies):
    if not proxies:
        return None
    return proxies[0].get("id")
