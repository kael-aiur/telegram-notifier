## Purpose
Defines the project module boundaries and deployment relationship between the Telegram starter, control server, and Vue web console.

## Requirements
### Requirement: Maven modules are separated by responsibility
The project SHALL define separate Maven modules for the Telegram Spring Boot starter and the Spring Boot control server. The Vue control web application SHALL be managed as frontend source code within the control server module rather than as a separate Maven module.

#### Scenario: Build includes platform modules
- **WHEN** the parent Maven project is inspected
- **THEN** it lists modules for `telegram-spring-boot-starter` and `telegram-notifier-control-server` only

#### Scenario: Frontend source lives inside control server
- **WHEN** the control server module directory is inspected
- **THEN** it contains `src/main/frontend/` with the Vue SPA source code (package.json, vite.config.js, src/)

### Requirement: Telegram integration is exposed through starter APIs
The Telegram starter SHALL expose Java interfaces for account sessions, authorization state, message events, scanning, and proxy updates without requiring the control server to use low-level Telegram client objects directly.

#### Scenario: Control server integrates through starter interfaces
- **WHEN** the control server starts Telegram account operations
- **THEN** it calls starter-provided interfaces instead of directly constructing Telegram client runtime objects

### Requirement: Control server owns product APIs and persistence
The control server SHALL own REST APIs, authentication, persistence, rule evaluation, push dispatch, statistics, and static SPA serving.

#### Scenario: API request is handled by control server
- **WHEN** a browser calls an `/api/**` endpoint
- **THEN** the request is handled by the Spring Boot control server

### Requirement: Vue assets are served by the packaged control server
The platform SHALL support packaging compiled Vue SPA assets into Spring Boot static resources for single-artifact deployment.

#### Scenario: Packaged application serves frontend
- **WHEN** the packaged control server receives a non-API browser route
- **THEN** it serves the Vue SPA entry point or static asset
