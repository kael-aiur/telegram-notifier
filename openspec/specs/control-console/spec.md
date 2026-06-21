## Purpose
Defines the Vue control console behavior for bootstrap, login, resource management, Telegram account interaction, and packaged asset serving.

## Requirements
### Requirement: Vue console supports bootstrap and login
The Vue SPA SHALL provide screens for administrator initialization when required and login after initialization.

#### Scenario: Bootstrap required
- **WHEN** the SPA loads and the backend reports that administrator initialization is required
- **THEN** the SPA shows the initialization flow instead of the normal login flow

### Requirement: Vue console manages core resources
The Vue SPA SHALL provide authenticated views for Telegram accounts, proxies, notification rules, push channels, scan settings, and statistics.

#### Scenario: Authenticated administrator opens console
- **WHEN** the administrator is authenticated
- **THEN** the SPA allows navigation to the core resource management views

### Requirement: Vue console supports Telegram login state interaction
The Vue SPA SHALL display Telegram account authorization state and provide the input required for the current state.

#### Scenario: Account waits for code
- **WHEN** an account state is waiting for code
- **THEN** the SPA shows a verification code input for that account

### Requirement: Vue build output is deployable through Spring Boot
The Vue SPA source code SHALL reside in `telegram-notifier-control-server/src/main/frontend/` and produce static build output that can be served by the Spring Boot control server.

#### Scenario: Browser route is refreshed
- **WHEN** a user refreshes a non-API SPA route in the browser
- **THEN** the Spring Boot server returns the SPA entry point so the Vue router can render the route

#### Scenario: Frontend build writes to server static resources
- **WHEN** the developer runs the Vite build from `src/main/frontend/`
- **THEN** the output is written to `src/main/resources/static/` within the same Maven module
