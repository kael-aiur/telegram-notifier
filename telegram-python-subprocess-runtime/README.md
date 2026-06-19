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

## Session boundary

The low-level Java/Python process boundary is represented by `TelegramSession`.
It is intentionally not a Telegram business object. It only owns:

- starting the Python process with runtime configuration such as executable,
  worker script, working directory, environment, timeout, data directory, and
  proxy chain;
- writing JSON Lines input strings to Python stdin;
- publishing Python output as raw JSON strings through
  `java.util.concurrent.Flow.Publisher<String>`;
- stopping the Python process;
- reporting process lifecycle status through `SessionStatus`.

`SessionStatus` describes the Python subprocess lifecycle only. Telegram
business states such as `WAIT_CODE`, `READY`, and `LOGGED_OUT` remain business
payload values interpreted by the adapter above `TelegramSession`.

`start(config)` accepts runtime configuration needed for Python to run and reach
Telegram, including proxies. Business values such as verification codes,
passwords, chat ids, and unread-message fetch parameters are sent later through
`send(String str)` as protocol inputs.

## JSON Lines envelope protocol

Java and Python communicate with one UTF-8 JSON object per line. Every input and
published output uses the same envelope shape:

```json
{
  "id": "java-1",
  "type": "input",
  "content": {}
}
```

Envelope fields:

- `id`: unique id for this envelope.
- `type`: protocol type.
- `content`: string or object payload.
- `replyInputId`: optional output field that identifies the Java input that
  directly triggered this output.

Java to Python currently supports:

- `input`

Python to Java currently supports:

- `reply`
- `log`
- `message`

Additional output types may be added later without changing the envelope shape.

### Input command

Business commands are carried inside `content`:

```json
{
  "id": "java-1",
  "type": "input",
  "content": {
    "command": "start",
    "accountId": 1,
    "apiId": 123456,
    "apiHash": "...",
    "dataDir": "data/telegram-client/accounts/1",
    "phoneNumber": "+8613800000000"
  }
}
```

Other command names include `submit_phone`, `submit_code`, `submit_password`,
`scan`, `fetch_unread`, and `stop`.

### Reply output

A command completion is always a `reply` with `replyInputId` equal to the input
id:

```json
{
  "id": "py-reply-1",
  "type": "reply",
  "replyInputId": "java-1",
  "content": {
    "ok": true,
    "result": {
      "status": {
        "accountId": 1,
        "state": "READY",
        "activeProxyId": 10
      }
    }
  }
}
```

Errors are also replies:

```json
{
  "id": "py-reply-2",
  "type": "reply",
  "replyInputId": "java-2",
  "content": {
    "ok": false,
    "error": {
      "code": "worker_error",
      "message": "proxy connect failed"
    }
  }
}
```

### Log output

Diagnostic output is published as `log` envelope JSON instead of plain stdout
text:

```json
{
  "id": "py-log-1",
  "type": "log",
  "content": {
    "level": "INFO",
    "message": "Telegram message handler registered",
    "time": "2026-06-19T12:00:00Z"
  }
}
```

The Java subprocess wrapper still drains stderr so the process cannot block. If
stderr contains data, Java wraps the sanitized text as a `type=log` envelope
before publishing it.

### Real-time message output

Real-time Telegram updates are `message` envelopes without `replyInputId`:

```json
{
  "id": "py-msg-1",
  "type": "message",
  "content": {
    "accountId": 1,
    "chatId": 100,
    "chatTitle": "chat",
    "chatType": "telegram",
    "senderId": 200,
    "senderName": "sender",
    "senderUsername": "sender",
    "receivedAt": "2026-06-19T12:00:00Z",
    "text": "message"
  }
}
```

### Command-scoped message output

A command that fetches unread messages emits each fetched message as a
`message` envelope with `replyInputId`, then emits a final `reply` with the same
`replyInputId` to mark the command complete:

```json
{
  "id": "java-fetch-1",
  "type": "input",
  "content": {
    "command": "fetch_unread",
    "chatId": 100,
    "limit": 50
  }
}
```

```json
{
  "id": "py-msg-2",
  "type": "message",
  "replyInputId": "java-fetch-1",
  "content": {
    "accountId": 1,
    "chatId": 100,
    "messageId": 9001,
    "receivedAt": "2026-06-19T12:01:00Z",
    "text": "unread message"
  }
}
```

```json
{
  "id": "py-reply-3",
  "type": "reply",
  "replyInputId": "java-fetch-1",
  "content": {
    "ok": true,
    "result": {
      "count": 1,
      "hasMore": false
    }
  }
}
```

## Runtime behavior

- `start` launches or restarts the account worker, starts the Python session,
  and sends an envelope input command.
- The worker reuses an existing account session when one is present.
- If no session exists, the worker enters `WAIT_PHONE` or sends a Telegram code
  and enters `WAIT_CODE`.
- `submitPhone`, `submitCode`, and `submitPassword` drive the real Telegram
  authorization flow through input envelopes.
- When authorization reaches `READY`, the worker listens for new Telegram
  messages and emits `message` envelopes.
- `stop` sends a stop input, then closes stdin and destroys the process if
  needed.
- `updateProxies` restarts the account worker so the new runtime proxy chain is
  used.
- SOCKS5 and HTTP proxies are supported. HTTPS proxy entries return a clear
  configuration error until Pyrogram support is verified.

Sensitive values must not appear in stdout, stderr, status errors, reply errors,
log envelopes, or Java logs:

- Telegram message text, except inside `message` protocol envelopes.
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
