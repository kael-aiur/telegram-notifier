## 1. Project Structure

- [x] 1.1 Add Maven modules for `telegram-spring-boot-starter`, `telegram-notifier-control-server`, and `telegram-notifier-control-web`
- [x] 1.2 Configure parent dependency management for Java 21, Spring Boot, testing, and module builds
- [x] 1.3 Add control server Spring Boot entry point and baseline application configuration
- [x] 1.4 Add Vue project scaffold and build output integration path for Spring Boot static resources
- [x] 1.5 Configure SPA routing fallback while preserving `/api/**` for backend APIs

## 2. Persistence Foundation

- [x] 2.1 Add SQLite JDBC dependency and default datasource configuration for `./data/telegram-notifier.db`
- [x] 2.2 Add Flyway migration support for SQLite
- [x] 2.3 Create initial schema migrations for administrator, Telegram accounts, proxies, scan settings, rules, channels, and statistics
- [x] 2.4 Add persistence helpers using Spring JDBC or MyBatis
- [x] 2.5 Add tests for database initialization and migration execution

## 3. Administrator Bootstrap and Auth

- [x] 3.1 Implement bootstrap status API
- [x] 3.2 Implement one-time administrator initialization API
- [x] 3.3 Implement administrator login and logout APIs
- [x] 3.4 Protect control APIs after initialization
- [x] 3.5 Add backend tests for bootstrap, repeated initialization rejection, login, and unauthenticated access

## 4. Telegram Starter

- [x] 4.1 Define starter interfaces for account sessions, authorization state, message subscription, scanning, and proxy updates
- [x] 4.2 Implement Telegram client session lifecycle management
- [x] 4.3 Implement Telegram authorization state mapping and state transition operations
- [x] 4.4 Implement normalized message event mapping with transient message body data
- [x] 4.5 Implement account scan support using frequency and unread age settings
- [x] 4.6 Add starter tests around state mapping, event normalization, and lifecycle behavior using fakes where needed

## 5. Telegram Account APIs

- [x] 5.1 Implement Telegram account CRUD APIs
- [x] 5.2 Implement account enable, disable, start, stop, and status APIs
- [x] 5.3 Implement login flow APIs for phone, verification code, and password submission
- [x] 5.4 Implement account scan setting APIs
- [x] 5.5 Wire account lifecycle APIs to the Telegram starter interfaces
- [x] 5.6 Add backend tests for account lifecycle, login state handling, and scan setting updates

## 6. Proxy Management

- [x] 6.1 Implement proxy server CRUD APIs with HTTP, HTTPS, and SOCKS5 protocol support
- [x] 6.2 Store optional proxy credentials without exposing secrets in normal API responses
- [x] 6.3 Implement account proxy binding APIs with priority ordering
- [x] 6.4 Implement starter-side session-level proxy failover across selected account proxies
- [x] 6.5 Expose account connection status including active proxy and proxy failure state
- [x] 6.6 Add tests for proxy validation, account binding order, credential masking, and failover behavior

## 7. Push Channels

- [x] 7.1 Implement push channel CRUD APIs with enable and disable support
- [x] 7.2 Define push channel abstraction with send and test operations
- [x] 7.3 Implement Bark channel configuration with default `https://api.day.app` server URL
- [x] 7.4 Implement Bark delivery using device key and URL-encoded notification content
- [x] 7.5 Implement channel test API and delivery result reporting
- [x] 7.6 Add tests for Bark URL construction, disabled channel skipping, and test delivery outcomes

## 8. Notification Rules and Privacy

- [x] 8.1 Implement rule CRUD APIs with enable and disable support
- [x] 8.2 Implement structured rule condition model with all/any/not groups and supported field operators
- [x] 8.3 Implement template rendering with timestamp, account, chat, sender, source label, and transient message body variables
- [x] 8.4 Implement rule-to-channel routing for matched events
- [x] 8.5 Ensure Telegram message body text is not persisted in rule hit records, delivery records, statistics, or application logs
- [x] 8.6 Add tests for matching, non-matching, template rendering, multi-channel dispatch, and message body non-persistence

## 9. Statistics

- [x] 9.1 Implement aggregate message processing counters
- [x] 9.2 Implement aggregate rule hit counters
- [x] 9.3 Implement delivery success and failure counters by rule and channel
- [x] 9.4 Implement authenticated statistics query APIs with time range filtering
- [x] 9.5 Add tests verifying statistics do not store Telegram message body text

## 10. Vue Control Console

- [x] 10.1 Implement bootstrap initialization and login views
- [x] 10.2 Implement authenticated layout and navigation for core management views
- [x] 10.3 Implement Telegram account management UI including login state interactions
- [x] 10.4 Implement proxy management and account proxy binding UI
- [x] 10.5 Implement notification rule editor with condition, template, source label, and channel selection controls
- [x] 10.6 Implement push channel management UI with Bark configuration and test action
- [x] 10.7 Implement statistics view for aggregate counts
- [x] 10.8 Add frontend tests for bootstrap flow, login flow, and representative resource forms

## 11. Integration Verification

- [x] 11.1 Add end-to-end backend integration tests covering bootstrap through authenticated API access
- [x] 11.2 Add integration tests for message event to rule match to Bark dispatch using test doubles
- [x] 11.3 Verify Vue build artifacts are served by the packaged Spring Boot application
- [x] 11.4 Run OpenSpec validation for `bootstrap-telegram-notifier-platform`
- [x] 11.5 Document default configuration, SQLite data file location, and first-run initialization steps
