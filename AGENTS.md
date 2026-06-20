# Telegram Notifier Architecture

This project is a single-user, self-hosted Telegram notification service built with Java 21, Spring Boot, SQLite, and Vue.

## Core Responsibilities

- Log in as one or more personal Telegram client accounts through a reusable Telegram starter module.
- Monitor Telegram message events and normalize them for rule evaluation.
- Evaluate administrator-defined notification rules against transient message event data.
- Render notification templates such as `{{receivedAt}} 收到来自{{sourceLabel}}的通知消息`.
- Dispatch rendered notifications through managed push channels, with Bark as the first supported channel.
- Manage proxy servers and account-level proxy chains for Telegram connectivity.
- Track aggregate message, rule hit, and delivery statistics without storing Telegram message bodies.

## Module Layout

- `telegram-spring-boot-starter`: reusable Telegram integration boundary.
  - Exposes `TelegramAccountSessionManager`, authorization state, message event, proxy, scanning, and subscription APIs.
  - Keeps Telegram client runtime details out of the control server.
  - The current implementation provides an in-memory runtime suitable for API integration and tests; production Telegram client internals should stay behind the same interfaces.
- `telegram-notifier-control-server`: Spring Boot backend, Vue frontend source, and deployment artifact.
  - Owns REST APIs, bootstrap/auth, SQLite persistence, Flyway migrations, rule evaluation, Bark dispatch, statistics, and SPA static serving.
  - Contains Vue SPA source at `src/main/frontend/`, builds static assets into `src/main/resources/static/`.
  - Provides views for bootstrap/login, accounts, proxies, rules, push channels, and statistics.

## Runtime Architecture

```text
Vue SPA
  |
  | /api/**
  v
Control Server
  |-- admin bootstrap/auth
  |-- Telegram account APIs
  |-- proxy management
  |-- notification rules
  |-- push channel dispatch
  |-- aggregate statistics
  |
  v
Telegram Starter
  |-- account session lifecycle
  |-- authorization state machine
  |-- message event subscription
  |-- account proxy updates
```

## Database

- Default database: SQLite.
- Default file location: `./data/telegram-notifier.db`.
- Config key: `telegram-notifier.data-dir`.
- Migrations: Flyway under `telegram-notifier-control-server/src/main/resources/db/migration`.
- Persistence style: Spring JDBC with explicit SQL.

SQLite is the default because this is a single-user service with lightweight configuration and statistics data. H2 is not the intended production database.

## Privacy Boundary

Telegram message body text is sensitive.

- Message text may exist in memory inside `TelegramMessageEvent`.
- Message text may be used for rule matching.
- Message text may be used as a template variable if a rule explicitly references it.
- Message text MUST NOT be stored in rule hit records, delivery records, statistics tables, or persistent logs.

The default notification template omits message text.

## Authentication

- First run calls `/api/system/bootstrap-status`.
- If no administrator exists, `/api/system/admin-init` creates the single administrator.
- After initialization, protected APIs require `X-Auth-Token`.
- `/api/auth/login` issues the token and `/api/auth/logout` revokes it.

## Push Channels

Push channels are managed resources selected by notification rules.

The initial Bark channel uses:

```text
https://api.day.app/{deviceKey}/{encodedContent}
```

If no Bark `serverUrl` is configured, `https://api.day.app` is used.

## Proxy Model

- Proxies support `HTTP`, `HTTPS`, and `SOCKS5`.
- Proxy credentials are stored but masked in normal API responses.
- Accounts bind zero or more proxies in priority order.
- Telegram connectivity uses session-level failover rather than per-request retry semantics.

## Development Commands

```bash
mvn test
```

Vue source lives in:

```text
telegram-notifier-control-server/src/main/frontend
```

The Vue build script writes assets to:

```text
telegram-notifier-control-server/src/main/resources/static
```
