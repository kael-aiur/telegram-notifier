## MODIFIED Requirements

### Requirement: Maven modules are separated by responsibility
The project SHALL define separate Maven modules for the Telegram Spring Boot starter and the Spring Boot control server. The Vue control web application SHALL be managed as frontend source code within the control server module rather than as a separate Maven module.

#### Scenario: Build includes platform modules
- **WHEN** the parent Maven project is inspected
- **THEN** it lists modules for `telegram-spring-boot-starter` and `telegram-notifier-control-server` only

#### Scenario: Frontend source lives inside control server
- **WHEN** the control server module directory is inspected
- **THEN** it contains `src/main/frontend/` with the Vue SPA source code (package.json, vite.config.js, src/)

#### Scenario: Frontend build output lands in static resources
- **WHEN** the Vue SPA is built from `src/main/frontend/`
- **THEN** the build output is written to `src/main/resources/static/` for Spring Boot to serve
