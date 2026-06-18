## Why

This project needs a self-hosted Telegram notification service that can log in as a Telegram client, monitor received messages, and send privacy-conscious notifications when configurable rules match. The initial platform should establish the Spring Boot module structure, Telegram integration boundary, single-user administration flow, proxy failover model, rule engine, managed push channels, and Vue-based control console.

## What Changes

- Create a multi-module Spring Boot platform with a Telegram integration starter and a control application.
- Introduce a Telegram account management flow for logging in personal Telegram accounts through a multi-step client authorization state machine.
- Add administrator bootstrap and login for a single-user self-hosted deployment.
- Add proxy server management with HTTP, HTTPS, and SOCKS5 proxy definitions, optional authentication, account-level proxy selection, and connection failover across selected proxies.
- Add scan settings for Telegram accounts, including polling frequency and unread age thresholds.
- Add notification rule management with condition matching and customizable notification templates.
- Enforce a privacy boundary where Telegram message bodies are transient rule/template variables only and are not persisted.
- Add push channel management with an initial Bark channel implementation.
- Add lightweight statistics for message counts, rule hit counts, and notification delivery outcomes without storing Telegram message text.
- Add a Vue single-page control console that is built independently and served by the Spring Boot application as static assets.

## Capabilities

### New Capabilities

- `platform-modules`: Defines the project module structure, Spring Boot application boundary, Telegram starter boundary, and Vue static asset hosting model.
- `admin-bootstrap-auth`: Covers first-run administrator initialization, single-user login, and access control for the control APIs and SPA.
- `telegram-account-management`: Covers Telegram account creation, client login state transitions, account lifecycle, and account scan configuration.
- `proxy-management`: Covers proxy server CRUD, proxy protocol and credential settings, account proxy bindings, and Telegram connection failover behavior.
- `notification-rules`: Covers rule CRUD, rule matching, template rendering, rule-to-channel routing, and message body privacy requirements.
- `push-channels`: Covers push channel CRUD, channel enablement, delivery testing, and the initial Bark push channel type.
- `notification-statistics`: Covers aggregate message, rule hit, and delivery statistics without persisting Telegram message bodies.
- `control-console`: Covers the Vue single-page control console features, API integration, build output, and Spring Boot static serving behavior.

### Modified Capabilities

- None.

## Impact

- Adds Spring Boot backend APIs for bootstrap/auth, Telegram accounts, proxies, scan settings, rules, push channels, and statistics.
- Adds a reusable Telegram Spring Boot starter that encapsulates Telegram client integration and exposes Java interfaces for the control application.
- Adds Vue frontend source and a build path that places compiled SPA assets under the Spring Boot static resources served by the control application.
- Adds persistence for configuration, account metadata, proxy definitions, rule definitions, channel definitions, and aggregate statistics.
- Adds dependencies for Telegram client integration, Spring Boot web/security/data, database migrations, template rendering, Vue tooling, and Bark HTTP delivery.
