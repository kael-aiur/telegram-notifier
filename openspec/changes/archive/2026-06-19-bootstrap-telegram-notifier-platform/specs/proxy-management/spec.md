## ADDED Requirements

### Requirement: Proxy servers can be managed
The system SHALL allow the administrator to create, view, update, enable, disable, and remove proxy server definitions.

#### Scenario: Create proxy server
- **WHEN** the administrator creates a proxy with protocol, host, and port
- **THEN** the system stores the proxy definition

### Requirement: Proxy protocols and credentials are supported
Proxy definitions SHALL support HTTP, HTTPS, and SOCKS5 protocols with optional username and password credentials.

#### Scenario: Store authenticated SOCKS5 proxy
- **WHEN** the administrator creates a SOCKS5 proxy with username and password
- **THEN** the system stores the proxy configuration with credentials protected from normal display

### Requirement: Accounts can bind multiple proxies
The system SHALL allow each Telegram account to select zero or more enabled proxies in priority order.

#### Scenario: Bind proxy chain to account
- **WHEN** the administrator assigns multiple proxies to an account
- **THEN** the system stores the selected proxies in the requested priority order

### Requirement: Telegram connections use selected proxies
Telegram account sessions SHALL use the account's selected proxies for Telegram client connections when proxies are configured.

#### Scenario: Account starts with proxies
- **WHEN** a Telegram account with selected proxies starts a session
- **THEN** the Telegram starter attempts the session through the selected proxies

### Requirement: Proxy failover is session-level
The Telegram starter SHALL retry selected proxies sequentially during startup, login, reconnect, or connectivity failure until a connection succeeds or the proxy chain is exhausted.

#### Scenario: First proxy fails
- **WHEN** an account session cannot connect through the first selected proxy
- **THEN** the starter attempts the next selected proxy

#### Scenario: All proxies fail
- **WHEN** all selected proxies fail for an account session
- **THEN** the account exposes a connection error state
