## ADDED Requirements

### Requirement: 未读消息在规则评估前必须经过阈值和去重检查
系统 SHALL 在 notification rule evaluation 之前，对 scan 返回的未读消息执行已推送记忆检查和账号 unread age threshold 检查。未通过这些检查的消息 MUST NOT 增加消息统计、规则命中统计或触发 push channel。

#### Scenario: 消息已经推送过
- **WHEN** scan 返回一条 `(accountId, chatId, messageId)` 已存在于已推送记忆中的未读消息
- **THEN** 系统 MUST NOT 对该消息执行 notification rule evaluation

#### Scenario: 消息未达到未读年龄阈值
- **WHEN** scan 返回一条当前未读消息，且消息年龄小于账号 unread age threshold
- **THEN** 系统 MUST NOT 对该消息执行 notification rule evaluation

#### Scenario: 消息通过基础检查
- **WHEN** scan 返回一条当前未读消息，且该消息未被记忆为已推送并达到账号 unread age threshold
- **THEN** 系统 MUST evaluate enabled notification rules against that message event

### Requirement: 规则条件和模板支持 messageId 字段
Notification rules SHALL be able to access the normalized Telegram `messageId` field in the same controlled event context as other message fields. The field MAY be used in structured conditions and notification templates.

#### Scenario: 使用 messageId 条件匹配
- **WHEN** a notification rule condition references field `messageId`
- **THEN** the rule evaluator MUST compare the condition against the event's Telegram message id value

#### Scenario: 模板渲染 messageId
- **WHEN** a matching notification rule template contains `{{messageId}}`
- **THEN** the rendered notification MUST include the event's Telegram message id value

### Requirement: 触发推送尝试后必须写入已推送记忆
When an unread message passes threshold and rule evaluation and triggers at least one push channel send attempt, the notification flow SHALL record the message in the already-notified memory using `(accountId, chatId, messageId)`.

#### Scenario: 匹配规则并尝试发送
- **WHEN** an unread message matches an enabled notification rule and the system attempts delivery through that rule's selected channels
- **THEN** the system MUST record the message as already notified after the send attempts are made

#### Scenario: 没有规则匹配
- **WHEN** an unread message passes threshold but matches no enabled notification rule
- **THEN** the system MUST NOT record the message as already notified

### Requirement: 已推送记忆不得扩大正文持久化范围
Notification rule processing SHALL preserve the existing privacy boundary for Telegram message bodies when integrating already-notified memory. The already-notified memory MUST NOT store message text, rendered content, or any message-body-derived value.

#### Scenario: 模板包含消息正文
- **WHEN** a rule template explicitly references `{{text}}` and the rendered notification includes Telegram message body text
- **THEN** the already-notified memory record for that message MUST still contain only metadata needed for deduplication and MUST NOT contain the rendered notification or body text
