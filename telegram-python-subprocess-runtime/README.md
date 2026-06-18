# Telegram Python Subprocess Runtime

This module implements `TelegramAccountSessionManager` by managing one CPython
worker process per Telegram account. The Python worker source is bundled inside
the application jar and extracted under `telegram.client.data-dir` at runtime.
The target system must provide Python and the required Python modules.

The control server depends only on the Java interface from
`telegram-spring-boot-starter`; this module is selected with:

```yaml
telegram:
  client:
    mode: PYTHON_SUBPROCESS
    api-id: 123456
    api-hash: your_api_hash
    data-dir: data/telegram-client
    python:
      executable: python3
```

By default no `worker-script` is needed. The bundled worker is extracted to:

```text
data/telegram-client/runtime/python-worker
```

If Python dependencies are missing, startup returns a diagnostic error such as:

```text
Missing Python modules: pyrogram,tgcrypto. Install dependencies before starting
real Telegram mode, for example: python3 -m pip install pyrogram tgcrypto
```

`telegram.client.python.worker-script` remains available for development
overrides.

Runtime behavior:

- `start` launches or restarts the account worker and sends a JSON command.
- `stop` sends `stop`, closes stdin, and destroys the process.
- `submitPhone`, `submitCode`, `submitPassword`, and `scan` are JSON commands.
- `updateProxies` restarts the account worker so the new startup proxy is used.

Java to Python commands are written to stdin as JSON Lines:

```json
{"type":"start","accountId":1,"apiId":123456,"apiHash":"...","dataDir":"data/telegram-client/accounts/1","proxies":[]}
{"type":"submit_phone","accountId":1,"phoneNumber":"+8613800000000"}
{"type":"submit_code","accountId":1,"code":"12345"}
{"type":"submit_password","accountId":1,"password":"secret"}
{"type":"scan","accountId":1}
{"type":"stop","accountId":1}
```

Python to Java events are written to stdout as JSON Lines:

```json
{"type":"status","accountId":1,"state":"WAIT_CODE"}
{"type":"status","accountId":1,"state":"READY","activeProxyId":10}
{"type":"message","accountId":1,"chatId":100,"chatTitle":"chat","chatType":"telegram","senderId":200,"senderName":"sender","senderUsername":"sender","receivedAt":"2026-06-18T12:00:00Z","text":"message"}
{"type":"error","accountId":1,"message":"proxy connect failed"}
```

The worker should write diagnostics to stderr. Stdout is reserved for protocol
events so Java can parse it reliably.
