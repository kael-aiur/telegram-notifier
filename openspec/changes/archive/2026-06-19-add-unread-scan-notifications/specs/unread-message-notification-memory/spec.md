## ADDED Requirements

### Requirement: 已推送 Telegram 消息必须持久化记忆
系统 SHALL 在 Java 控制端持久化已触发推送尝试的 Telegram 消息元数据，用于避免同一条仍未读消息在后续 scan 中重复触发通知。持久化键 MUST 至少包含 `accountId`、`chatId`、`messageId`，并记录 `notifiedAt`。

#### Scenario: 记录已推送消息
- **WHEN** 一条未读 Telegram 消息通过年龄阈值检查、匹配至少一条启用的通知规则，并触发一次或多次 push channel 发送尝试
- **THEN** 系统 MUST 持久化该消息的 `accountId`、`chatId`、`messageId` 和 `notifiedAt`

#### Scenario: 重复 scan 返回已记忆消息
- **WHEN** 后续 scan 再次返回相同 `accountId`、`chatId`、`messageId` 的未读消息
- **THEN** 系统 MUST 跳过规则评估、统计命中和推送发送

### Requirement: 已推送记忆不得保存 Telegram 消息正文
已推送消息记忆 SHALL 只保存通知去重所需元数据，MUST NOT 保存 Telegram 消息正文、caption、正文摘要或正文 hash。

#### Scenario: 消息正文参与规则匹配后记录去重
- **WHEN** 一条未读消息的正文在内存中用于 notification rule 匹配并触发推送尝试
- **THEN** 已推送记忆记录 MUST NOT 包含该消息正文或由正文派生的持久字段

### Requirement: 未匹配规则的未读消息不得被记忆为已推送
系统 SHALL 只对触发推送尝试的消息写入已推送记忆。未通过年龄阈值或未匹配任何启用规则的消息 MUST NOT 被记忆为已推送。

#### Scenario: 未读消息未达到年龄阈值
- **WHEN** scan 返回一条未读消息且其 `receivedAt` 距当前时间小于账号 `unreadAgeThreshold`
- **THEN** 系统 MUST NOT 记录该消息为已推送

#### Scenario: 未读消息没有匹配规则
- **WHEN** scan 返回一条达到年龄阈值的未读消息但没有任何启用 notification rule 匹配
- **THEN** 系统 MUST NOT 记录该消息为已推送
