## ADDED Requirements

### Requirement: Push channels can be managed
The system SHALL allow the administrator to create, view, update, enable, disable, remove, and test push channels.

#### Scenario: Create push channel
- **WHEN** the administrator creates a push channel with valid type-specific configuration
- **THEN** the system stores the channel and makes it selectable by rules

### Requirement: Disabled channels are not used for delivery
The system SHALL skip disabled push channels during rule dispatch.

#### Scenario: Rule selects disabled channel
- **WHEN** a matching rule references a disabled push channel
- **THEN** the system does not send a notification through that channel

### Requirement: Bark channel is supported
The system SHALL support a Bark push channel type configured with server URL and device key.

#### Scenario: Send Bark notification
- **WHEN** a Bark channel receives rendered notification content
- **THEN** the system sends an HTTP request to the configured Bark endpoint using the device key and encoded content

### Requirement: Bark channel can use default server URL
The system SHALL allow Bark channels to use `https://api.day.app` as the default server URL when no custom server URL is configured.

#### Scenario: Bark server URL omitted
- **WHEN** the administrator creates a Bark channel without a custom server URL
- **THEN** the system uses `https://api.day.app` for delivery

### Requirement: Channel tests report delivery outcome
The system SHALL allow the administrator to send a test notification through a configured push channel and receive the delivery outcome.

#### Scenario: Test Bark channel succeeds
- **WHEN** the administrator tests a valid Bark channel
- **THEN** the system returns a successful test result
