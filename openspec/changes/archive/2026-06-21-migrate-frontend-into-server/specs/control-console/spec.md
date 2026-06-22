## MODIFIED Requirements

### Requirement: Vue build output is deployable through Spring Boot
The Vue SPA source code SHALL reside in `telegram-notifier-control-server/src/main/frontend/` and produce static build output that can be served by the Spring Boot control server.

#### Scenario: Browser route is refreshed
- **WHEN** a user refreshes a non-API SPA route in the browser
- **THEN** the Spring Boot server returns the SPA entry point so the Vue router can render the route

#### Scenario: Frontend build writes to server static resources
- **WHEN** the developer runs the Vite build from `src/main/frontend/`
- **THEN** the output is written to `src/main/resources/static/` within the same Maven module
