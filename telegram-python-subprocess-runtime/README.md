# Telegram Python Subprocess Runtime

This module implements `TelegramAccountSessionManager` by managing one CPython
worker process per Telegram account. The Python worker source is bundled inside
the application jar and extracted under `telegram.client.data-dir` at runtime.
The target system must provide Python and the required Python modules. The
bundled worker uses Pyrogram for Telegram connectivity.

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

Recommended dependency installation:

```bash
python3 -m pip install pyrogram tgcrypto
```

Runtime data layout:

```text
data/telegram-client/
  accounts/
    1/
      telegram-account.session
  runtime/
    python-worker/
      main.py
      telegram_worker/
```

All worker code extracted from the jar and all Telegram session files stay
under `telegram.client.data-dir`.

Runtime behavior:

- `start` launches or restarts the account worker and sends a JSON command.
- The worker reuses an existing account session when one is present.
- If no session exists, the worker enters `WAIT_PHONE` or sends a Telegram code
  and enters `WAIT_CODE`.
- `submitPhone`, `submitCode`, and `submitPassword` drive the real Telegram
  authorization flow.
- When authorization reaches `READY`, the worker listens for new Telegram
  messages and emits message events to Java.
- `stop` sends `stop`, closes stdin, and destroys the process.
- `updateProxies` restarts the account worker so the new startup proxy is used.
- SOCKS5 and HTTP proxies are supported. HTTPS proxy entries return a clear
  configuration error until Pyrogram support is verified.

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

Sensitive values must not appear in stdout, stderr, status errors, or Java logs:

- Telegram message text, except inside `message` protocol events.
- Telegram verification codes.
- Two-step verification passwords.
- Proxy passwords.
- Telegram API hash values.

Manual real Telegram validation:

1. Configure `telegram.client.mode=PYTHON_SUBPROCESS`, `api-id`, `api-hash`,
   `data-dir`, and `telegram.client.python.executable`.
2. Ensure Python dependencies are installed with `python3 -m pip install
   pyrogram tgcrypto`.
3. Start the control server jar with an empty data directory.
4. Create a Telegram account with a phone number and start it.
5. Submit the Telegram verification code, then submit the two-step password if
   the account requires one.
6. Confirm the account reaches `READY`.
7. Send a message to that Telegram account and confirm the notifier receives a
   message event and evaluates rules.

Manual proxy validation:

1. Create a SOCKS5 or HTTP proxy in the control server.
2. Bind it to the Telegram account as the first proxy in the account chain.
3. Start or restart the account and confirm `activeProxyId` matches the proxy.
4. Change the proxy binding and confirm the account reconnects with the new
   `activeProxyId` or returns a clear connection error.

Real Telegram validation is intentionally manual. Default Maven tests use helper
unit tests and fake workers so CI does not depend on Telegram account security
checks, SMS/code delivery, or proxy availability.
