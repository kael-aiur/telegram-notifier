## Purpose
Defines aggregate message, rule hit, delivery outcome, and query statistics while excluding Telegram message body persistence.

## Requirements
### Requirement: Message processing counts are tracked
The system SHALL track aggregate counts for processed Telegram message events without storing Telegram message body text.

#### Scenario: Message event processed
- **WHEN** a Telegram message event is processed
- **THEN** the system increments the relevant aggregate message count

### Requirement: Rule hit counts are tracked
The system SHALL track aggregate counts for notification rule matches.

#### Scenario: Rule matches message
- **WHEN** a message event matches a notification rule
- **THEN** the system increments the rule hit count for that rule

### Requirement: Delivery outcomes are tracked
The system SHALL track notification delivery success and failure counts by channel and rule.

#### Scenario: Delivery fails
- **WHEN** a push channel delivery attempt fails
- **THEN** the system records a failed delivery outcome without storing the Telegram message body

### Requirement: Statistics can be queried
The system SHALL expose authenticated statistics APIs for aggregate message counts, rule hit counts, and delivery outcome counts.

#### Scenario: Administrator views statistics
- **WHEN** the administrator requests statistics for a time range
- **THEN** the system returns aggregate counts for that range
