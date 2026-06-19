## ADDED Requirements

### Requirement: 账号未读扫描由 Java 控制端调度
系统 SHALL 根据启用账号的 scan frequency 周期性发起未读扫描，并由 Java 控制端在每次 scan 中提供本次要扫描的 chatIds。

#### Scenario: 到达账号扫描时间
- **WHEN** 一个启用且已授权就绪的 Telegram 账号到达其下一次 scan 时间，并且该账号配置了 monitored chatIds
- **THEN** Java 控制端 MUST 调用 Telegram session scan，并在请求中包含账号 id 和当前配置的 chatIds

#### Scenario: 账号没有配置 monitored chatIds
- **WHEN** 一个账号到达 scan 时间但没有配置任何 monitored chatIds
- **THEN** Java 控制端 MUST NOT 对该账号发起 Telegram unread scan

### Requirement: 账号未读年龄阈值由 Java 控制端应用
系统 SHALL 在 Java 控制端使用账号的 unread age threshold 判断 scan 返回的未读消息是否具备通知资格。Python worker MUST NOT be required to receive or apply this threshold.

#### Scenario: 未读消息未超过阈值
- **WHEN** Python scan 返回一条当前未读消息，且当前时间减去消息 `receivedAt` 小于该账号 unread age threshold
- **THEN** Java 控制端 MUST NOT dispatch notification rules for that message

#### Scenario: 未读消息超过阈值
- **WHEN** Python scan 返回一条当前未读消息，且当前时间减去消息 `receivedAt` 大于或等于该账号 unread age threshold
- **THEN** Java 控制端 MAY pass that message to notification rule evaluation if it has not already been notified

## MODIFIED Requirements

### Requirement: Telegram message events are normalized
The Telegram starter SHALL provide normalized message events to the control server for rule evaluation. Normalized events MUST include account, chat, sender, timestamp, stable Telegram message id, and transient message body data when available.

#### Scenario: Incoming message is received
- **WHEN** the Telegram client receives an incoming message update
- **THEN** the control server receives a normalized event containing account, chat, sender, timestamp, message id, and transient message body data

#### Scenario: Unread scan message is received
- **WHEN** a Python worker scan returns a currently unread Telegram message
- **THEN** the control server receives a normalized event containing account id, chat id, Telegram message id, received timestamp, and available source metadata
