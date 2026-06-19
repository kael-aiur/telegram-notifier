## Context

The repository currently contains a Maven parent project and OpenSpec configuration, but no application modules. The target product is a single-user, self-hosted Telegram notification service built on Spring Boot and Java 21. It logs in as a Telegram client account, monitors incoming Telegram messages, evaluates configurable rules, and sends notification text through managed push channels.

The Telegram integration should be reusable outside the control application, so it belongs in a starter module that exposes Java interfaces and hides the client library lifecycle. The control application owns persistence, REST APIs, authentication, rule management, push channel management, statistics, and serving the Vue single-page console.

Telegram message bodies are sensitive. They may be used transiently for matching and template rendering, but they must not be persisted as stored message history, delivery records, or statistics.

## Goals / Non-Goals

**Goals:**

- Establish a Maven multi-module structure with a Telegram Spring Boot starter and a control application.
- Provide a clear Java API boundary between the Telegram integration and the control backend.
- Support first-run administrator initialization followed by single-user login.
- Support Telegram account login as a client through a multi-step authorization state machine.
- Support account scan settings, including polling frequency and unread age thresholds.
- Support proxy server management and account-level proxy failover for Telegram connections.
- Support notification rules with condition matching and templates.
- Support managed push channels, with Bark as the initial channel type.
- Persist configuration, metadata, and aggregate statistics without persisting Telegram message bodies.
- Serve a compiled Vue SPA from the Spring Boot control application.

**Non-Goals:**

- Multi-user or role-based administration beyond one administrator account.
- Telegram Bot API support as the primary Telegram integration model.
- Persisting full Telegram message history or message body text.
- Full-text message search or audit replay.
- Multiple push channel implementations beyond Bark in the initial platform.
- Distributed deployment, horizontal scaling, or multi-node coordination.

## Decisions

### Use a multi-module Maven structure

Create three project modules under the parent:

- `telegram-spring-boot-starter`: reusable Telegram client integration.
- `telegram-notifier-control-server`: Spring Boot backend, persistence, API, security, rule engine, push dispatch, and static SPA serving.
- `telegram-notifier-control-web`: Vue single-page application source.

Rationale: this keeps the Telegram integration reusable and keeps frontend build concerns separate from backend code. A two-module layout with frontend nested under the control module was considered, but the three-module layout gives Maven, CI, and ownership boundaries a clearer shape.

### Encapsulate Telegram client integration behind starter interfaces

The starter should expose application-facing interfaces such as account session management, authorization state handling, message event subscription, scanning, and proxy updates. The control server should not depend directly on low-level Telegram client objects.

Rationale: Telegram client libraries tend to have long-lived sessions, native/runtime dependencies, authorization states, and asynchronous updates. Hiding those details prevents TDLib-specific behavior from leaking into API controllers and rule engine code.

### Model Telegram login as a state machine

Telegram account login should be represented through explicit states such as waiting for phone number, waiting for code, waiting for password, ready, logged out, and error. API operations advance the state rather than attempting to complete login in one request.

Rationale: Telegram client authorization is inherently multi-step and may require a verification code or two-factor password. A state machine gives the Vue console predictable UI behavior and avoids brittle one-shot login endpoints.

### Use session-level proxy failover

Proxy management should store reusable proxy definitions with protocol, host, port, and optional credentials. A Telegram account can bind one or more proxies in priority order. For startup, login, reconnect, and runtime connectivity failures, the starter attempts selected proxies sequentially until one succeeds.

Rationale: Telegram client integration is long-lived and connection-oriented, so failover should be managed at session/reconnect boundaries, not as per-request HTTP retry logic. This matches the operational model better and avoids implying that each Telegram operation opens an isolated request.

### Keep message body data transient

Incoming message bodies should exist only inside in-memory event objects used by the rule engine and template renderer. Persistent records may store counts, timestamps, rule IDs, account IDs, channel IDs, delivery status, and non-sensitive error summaries, but not message text.

Rationale: the project is self-hosted but still handles private Telegram content. The useful product behavior is notification and statistics, not archival storage. Keeping message bodies transient reduces privacy risk and simplifies storage requirements.

### Implement rules as structured filters plus templates

Rules should be stored as structured condition trees with supported fields and operators, then routed to one or more push channels. Templates render notification text from a controlled context that may include timestamps, account metadata, chat metadata, sender metadata, source labels, and the transient message body variable.

Rationale: the expected rule set is event filtering, not a general business rules problem. A structured rule model is easier to validate, render in the Vue UI, and secure than a general scripting engine.

### Abstract push channels with Bark first

Push delivery should use a channel abstraction with type-specific configuration and a common send/test API. The initial implementation is Bark, configured with a server URL and device key. Rule delivery routes rendered notification content to the selected enabled channels.

Rationale: Bark is simple enough for the first channel, while the abstraction leaves room for future Webhook, email, or other providers without changing rule semantics.

### Serve Vue as a static SPA from Spring Boot

The Vue module builds static assets that are copied into the control server static resources for packaged deployment. Backend routing reserves `/api/**` for REST APIs and serves the SPA entry point for browser routes.

Rationale: this keeps deployment simple for a single-user self-hosted app: one Spring Boot artifact can serve both API and UI. The Vue project remains independently developed and built.

### Use aggregate statistics instead of message storage

Statistics should be derived from event processing and delivery outcomes. Store aggregate counters and delivery records such as message count, rule hit count, successful delivery count, and failed delivery count.

Rationale: this satisfies visibility needs without creating a message archive. If deduplication is needed, use non-reversible fingerprints and avoid storing raw content.

### Use SQLite as the default embedded database

Use SQLite as the default runtime database, with the database file stored under a configurable data directory such as `./data/telegram-notifier.db`. Use Flyway for schema migrations and prefer Spring JDBC or MyBatis over JPA/Hibernate for persistence access.

Rationale: the product is a single-user self-hosted tool with configuration data, lightweight statistics, and no message body storage. SQLite gives simple deployment, backup, and long-term file readability. H2 was considered, but it is better suited for tests, demos, and development than as the default long-running application database. JPA/Hibernate with SQLite adds dialect and DDL friction that is avoidable for this data model.

## Risks / Trade-offs

- TDLib/native dependency complexity -> Encapsulate setup and lifecycle inside the starter and keep the control server dependent on stable interfaces.
- Telegram authorization edge cases -> Store authorization state explicitly and make login APIs idempotent where possible.
- Proxy failover can interrupt active sessions -> Treat proxy switching as reconnect behavior and expose current proxy/status in account state.
- Rule templates could accidentally include sensitive fields -> Provide safe defaults, make message body use explicit, and never persist rendered content unless it is needed for delivery diagnostics.
- Rule matching may grow beyond simple filters -> Start with structured operators and condition groups; defer general scripting until a concrete use case appears.
- Vue static build can drift from server routes -> Reserve `/api/**`, add SPA fallback handling, and keep build output integration in the Maven build or documented build task.
- Single-user assumptions may become limiting -> Keep data ownership simple now, but avoid hard-coding admin identity into unrelated domain records.
- SQLite concurrency is limited compared with server databases -> Keep writes short, use connection pooling conservatively, and avoid long-running transactions.

## Migration Plan

This is a new platform bootstrap with no existing runtime data to migrate.

1. Add the Maven modules and establish the Spring Boot application packaging.
2. Add persistence and migration tooling before introducing domain tables.
3. Add administrator bootstrap so first deployment can be initialized safely.
4. Add domain APIs and Vue views incrementally behind authenticated access.
5. Add Telegram starter integration and wire it to account lifecycle APIs.
6. Add rule evaluation, push delivery, and aggregate statistics.

Rollback during initial development is module-level: remove or disable the newly introduced module or feature slice before deployment. After deployment, database migrations should be forward-only with explicit corrective migrations when needed.

## Open Questions

- Which session model should the control server use: cookie-based server sessions or stateless bearer tokens?
- Should scan settings be global defaults with per-account overrides, or only per-account settings?
- Should Bark notifications support optional title, category, sound, icon, URL, or group fields in the initial implementation, or only the basic message endpoint?
- Should statistics be stored as raw delivery event rows plus derived aggregates, or as pre-aggregated daily/hourly counters only?
