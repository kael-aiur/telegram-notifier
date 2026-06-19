class ProxyConfigurationError(ValueError):
    pass


def select_proxy(proxies):
    if not proxies:
        return None, None
    proxy = proxies[0]
    return proxy.get("id"), to_pyrogram_proxy(proxy)


def to_pyrogram_proxy(proxy):
    if not proxy:
        return None
    protocol = str(proxy.get("protocol") or "").upper()
    if protocol == "SOCKS5":
        scheme = "socks5"
    elif protocol == "HTTP":
        scheme = "http"
    elif protocol == "HTTPS":
        raise ProxyConfigurationError("HTTPS proxy is not supported by the Python Telegram worker; use HTTP or SOCKS5")
    else:
        raise ProxyConfigurationError(f"Unsupported proxy protocol: {protocol or 'blank'}")

    hostname = str(proxy.get("host") or "").strip()
    port = proxy.get("port")
    if not hostname:
        raise ProxyConfigurationError("Proxy host is required")
    try:
        port = int(port)
    except (TypeError, ValueError) as exc:
        raise ProxyConfigurationError("Proxy port must be a number") from exc
    if port <= 0 or port > 65535:
        raise ProxyConfigurationError("Proxy port must be between 1 and 65535")

    value = {
        "scheme": scheme,
        "hostname": hostname,
        "port": port,
    }
    username = proxy.get("username")
    password = proxy.get("password")
    if username:
        value["username"] = str(username)
    if password:
        value["password"] = str(password)
    return value
