## MODIFIED Requirements

### Requirement: Notification rules can be managed
The system SHALL allow the administrator to create, view, update, enable, disable, and remove notification rules scoped to a specific Telegram account. Each notification rule SHALL belong to exactly one account, and rule management operations SHALL be performed within the owning account's scope rather than as global resources.

#### Scenario: Create enabled rule for an account
- **WHEN** the administrator creates a rule with conditions, template, and channels within an account's scope
- **THEN** the system stores the rule associated with that account and includes it in future evaluations of that account's messages

#### Scenario: Rule must belong to an account
- **WHEN** the administrator attempts to manage notification rules
- **THEN** every rule SHALL be associated with exactly one account, and global rules unaffiliated with an account SHALL NOT exist

### Requirement: 未读消息在规则评估前必须经过阈值和去重检查
系统 SHALL 在 notification rule evaluation 之前，对 scan 返回的未读消息执行已推送记忆检查和账号 unread age threshold 检查。未通过这些检查的消息 MUST NOT 增加消息统计、规则命中统计或触发 push channel。通过基础检查的消息 MUST 仅评估其所属账号的启用 notification rules。

#### Scenario: 消息已经推送过
- **WHEN** scan 返回一条 `(accountId, chatId, messageId)` 已存在于已推送记忆中的未读消息
- **THEN** 系统 MUST NOT 对该消息执行 notification rule evaluation

#### Scenario: 消息未达到未读年龄阈值
- **WHEN** scan 返回一条当前未读消息，且消息年龄小于账号 unread age threshold
- **THEN** 系统 MUST NOT 对该消息执行 notification rule evaluation

#### Scenario: 消息通过基础检查并按账号作用域评估规则
- **WHEN** scan 返回一条当前未读消息，且该消息未被记忆为已推送并达到账号 unread age threshold
- **THEN** 系统 MUST evaluate only the enabled notification rules belonging to that message's account against that message event

### Requirement: 触发推送尝试后必须写入已推送记忆
When an unread message passes threshold and rule evaluation and triggers at least one push channel send attempt, the notification flow SHALL record the message in the already-notified memory using `(accountId, chatId, messageId)`, together with the matched rule(s) and the delivery result of each selected channel.

#### Scenario: 匹配规则并尝试发送
- **WHEN** an unread message matches an enabled notification rule of its account and the system attempts delivery through that rule's selected channels
- **THEN** the system MUST record the message as already notified, along with the matched rule(s) and each selected channel's delivery result (success or failure), after the send attempts are made

#### Scenario: 没有规则匹配
- **WHEN** an unread message passes threshold but matches no enabled notification rule of its account
- **THEN** the system MUST NOT record the message as already notified
