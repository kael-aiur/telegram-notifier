SENSITIVE_KEYS = (
    "apiHash",
    "api_hash",
    "phoneCode",
    "phone_code",
    "password",
    "proxyPassword",
    "proxy_password",
)


def sanitize(value, secrets=None):
    text = "" if value is None else str(value)
    for secret in secrets or ():
        if secret:
            text = text.replace(str(secret), "******")
    for key in SENSITIVE_KEYS:
        text = _mask_key_value(text, key)
    return text


def safe_exception(error, secrets=None):
    message = str(error) or error.__class__.__name__
    return sanitize(message, secrets)


def _mask_key_value(text, key):
    markers = (
        f"{key}=",
        f"{key}:",
        f'"{key}":',
        f"'{key}':",
    )
    output = text
    for marker in markers:
        start = output.find(marker)
        while start >= 0:
            value_start = start + len(marker)
            while value_start < len(output) and output[value_start] in " '\"":
                value_start += 1
            value_end = value_start
            while value_end < len(output) and output[value_end] not in " ,;}'\"]":
                value_end += 1
            if value_end > value_start:
                output = output[:value_start] + "******" + output[value_end:]
            start = output.find(marker, value_start + 6)
    return output
