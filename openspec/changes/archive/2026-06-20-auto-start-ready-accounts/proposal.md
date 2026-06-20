# Auto-start READY accounts on service restart

## Problem

Every time the service restarts, the user must manually click "Start" for each Telegram account that was previously monitoring messages. This is tedious and error-prone — if the server reboots at night, monitoring is silently lost until someone notices.

## Solution

Add a startup hook that queries SQLite for accounts whose `authorization_state = 'READY'` and automatically starts their Python subprocesses. No new database columns or state enums needed.

## Why `READY` is sufficient

`READY` means the account has a valid Telegram session and was actively monitoring before shutdown. Non-READY states (`LOGGED_OUT`, `ERROR`, `WAIT_PHONE`, etc.) indicate incomplete login or broken sessions — starting a subprocess for these accounts would not enable monitoring anyway.

## Scope

- **In scope**: Auto-start on service boot, logging
- **Out of scope**: New DB columns, frontend changes, retry/backoff logic, configurable auto-start toggle

## Implementation sketch

```
Service starts
  │
  ├─ Spring context initializes
  ├─ SQLite / data source ready
  ├─ ApplicationReadyEvent fires
  │   └─ Query: SELECT * FROM telegram_accounts
  │             WHERE authorization_state = 'READY' AND enabled = 1
  │   └─ For each account:
  │       ├─ Build TelegramAccountConfig (with proxy bindings)
  │       ├─ Call sessions.start(config)
  │       ├─ Log: "auto-started account {id} ({displayName})"
  │       └─ On failure: log warning, continue to next account
  │
  └─ Service ready, accepting requests
```

## Key files

| File | Change |
|------|--------|
| `Services.java` or new `AutoStartListener.java` | Add `ApplicationReadyEvent` listener |
| `TelegramAccountService` | Expose a method to get READY+enabled accounts and build config |
| No migration needed | `authorization_state` column already exists |

## Failure handling

- If `sessions.start()` throws or returns ERROR: log a warning, move on to the next account
- The `desired_state` stays as-is (no state change) — the account will be retried on next restart
- This matches the behavior of a manual start that fails: the user sees the error and can retry

## Risks

| Risk | Mitigation |
|------|------------|
| Startup delay if many accounts | Start accounts sequentially with logging; could parallelize later if needed |
| Stale READY state from crash | If session file is corrupted, start will fail gracefully and log |
| Race with scheduler | Scheduler only scans READY accounts that have running workers — no conflict since we start workers before scheduler's first tick |
