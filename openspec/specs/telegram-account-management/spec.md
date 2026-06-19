## Purpose
Defines Telegram account management, authorization state transitions, login input APIs, scan settings, and normalized message events.

## Requirements
### Requirement: Telegram accounts can be managed
The system SHALL allow the administrator to create, view, update, enable, disable, and remove Telegram account records.

#### Scenario: Create Telegram account
- **WHEN** the administrator creates a Telegram account with required metadata
- **THEN** the system stores the account record and makes it available for login

### Requirement: Telegram login uses explicit authorization states
The system SHALL represent Telegram client login through explicit states including waiting for phone, waiting for code, waiting for password, ready, logged out, and error.

#### Scenario: Login requires verification code
- **WHEN** a Telegram account login is started and Telegram requires a verification code
- **THEN** the account state becomes waiting for code

#### Scenario: Login completes
- **WHEN** all required Telegram authorization inputs have been accepted
- **THEN** the account state becomes ready

### Requirement: Login state can be advanced through APIs
The system SHALL expose account login operations that submit the required authorization input for the account's current state.

#### Scenario: Submit code for waiting account
- **WHEN** an account is waiting for code and the administrator submits a code
- **THEN** the system forwards the code to the Telegram session and returns the updated authorization state

### Requirement: Account scan settings are configurable
The system SHALL allow each Telegram account to configure scan frequency and unread age threshold.

#### Scenario: Update scan settings
- **WHEN** the administrator updates an account's scan frequency and unread age threshold
- **THEN** future scans use the updated settings

### Requirement: Telegram message events are normalized
The Telegram starter SHALL provide normalized message events to the control server for rule evaluation.

#### Scenario: Incoming message is received
- **WHEN** the Telegram client receives an incoming message update
- **THEN** the control server receives a normalized event containing account, chat, sender, timestamp, and transient message body data
