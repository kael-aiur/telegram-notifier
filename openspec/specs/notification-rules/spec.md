## Purpose
Defines administrator-managed notification rules, condition evaluation, template rendering, routing, and the privacy boundary for message bodies.

## Requirements
### Requirement: Notification rules can be managed
The system SHALL allow the administrator to create, view, update, enable, disable, and remove notification rules.

#### Scenario: Create enabled rule
- **WHEN** the administrator creates a rule with conditions, template, and channels
- **THEN** the system stores the rule and includes it in future evaluations

### Requirement: Rules support structured conditions
Rules SHALL support structured condition groups and operators for matching normalized Telegram message event fields.

#### Scenario: Message matches all conditions
- **WHEN** a message event satisfies every condition in an all-condition rule
- **THEN** the rule is considered matched

#### Scenario: Message does not match
- **WHEN** a message event does not satisfy the rule condition tree
- **THEN** the rule is not dispatched to push channels

### Requirement: Rules render notification templates
Rules SHALL render notification text from a controlled template context when a message event matches.

#### Scenario: Render timestamp and source label
- **WHEN** a matching rule template is `{{receivedAt}} received notification from {{sourceLabel}}`
- **THEN** the rendered notification includes the event timestamp and configured source label

### Requirement: Message body is transient only
The system SHALL NOT persist Telegram message body text in rule definitions, rule hit records, delivery records, statistics, or logs intended for persistent storage.

#### Scenario: Rule uses message body for matching
- **WHEN** a rule condition evaluates the message body text
- **THEN** the body is used only from the in-memory message event and is not stored

#### Scenario: Template omits message body
- **WHEN** a rule template does not reference the message body variable
- **THEN** the delivered notification contains no Telegram message body text

### Requirement: Rules route to selected channels
Rules SHALL dispatch rendered notifications to the enabled push channels selected by the rule.

#### Scenario: Rule has two enabled channels
- **WHEN** a message matches a rule with two enabled channels
- **THEN** the system attempts delivery through both channels
