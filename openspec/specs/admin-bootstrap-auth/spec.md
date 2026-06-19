## Purpose
Defines the bootstrap and single-administrator authentication behavior required before protected control APIs can be used.

## Requirements
### Requirement: System exposes bootstrap status
The system SHALL expose whether administrator initialization is required before normal login is allowed.

#### Scenario: No administrator exists
- **WHEN** the bootstrap status endpoint is called and no administrator account exists
- **THEN** the system reports that administrator initialization is required

#### Scenario: Administrator exists
- **WHEN** the bootstrap status endpoint is called and an administrator account exists
- **THEN** the system reports that administrator initialization is not required

### Requirement: First administrator can be initialized once
The system SHALL allow creating the administrator account only when no administrator account exists.

#### Scenario: Initialize first administrator
- **WHEN** no administrator exists and valid initialization credentials are submitted
- **THEN** the system creates the administrator account and disables further initialization

#### Scenario: Reject repeated initialization
- **WHEN** an administrator already exists and initialization is submitted
- **THEN** the system rejects the request

### Requirement: Login is required after initialization
The system SHALL require authenticated administrator access for control APIs after initialization is complete.

#### Scenario: Unauthenticated API access after initialization
- **WHEN** initialization is complete and an unauthenticated request calls a protected API
- **THEN** the system rejects the request

### Requirement: Single administrator model is enforced
The system SHALL support a single administrator account for the initial platform.

#### Scenario: Attempt to create additional administrator
- **WHEN** a request attempts to create another administrator account after initialization
- **THEN** the system prevents creating the additional administrator
