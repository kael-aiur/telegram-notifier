## MODIFIED Requirements

### Requirement: 已推送 Telegram 消息必须持久化记忆
系统 SHALL 在 Java 控制端持久化已触发推送尝试的 Telegram 消息元数据，用于避免同一条仍未读消息在后续 scan 中重复触发通知，并作为推送记录供管理员查看。持久化键 MUST 至少包含 `accountId`、`chatId`、`messageId`，并记录 `notifiedAt`、命中的规则以及各投递通道的投递结果（成功或失败）。

#### Scenario: 记录已推送消息
- **WHEN** 一条未读 Telegram 消息通过年龄阈值检查、匹配至少一条启用的通知规则，并触发一次或多次 push channel 发送尝试
- **THEN** 系统 MUST 持久化该消息的 `accountId`、`chatId`、`messageId`、`notifiedAt`、命中的规则以及各投递通道的投递结果

#### Scenario: 重复 scan 返回已记忆消息
- **WHEN** 后续 scan 再次返回相同 `accountId`、`chatId`、`messageId` 的未读消息
- **THEN** 系统 MUST 跳过规则评估、统计命中和推送发送

### Requirement: 已推送记忆不得保存 Telegram 消息正文
已推送消息记忆 SHALL 只保存通知去重与推送记录所需元数据，MUST NOT 保存 Telegram 消息正文、caption、正文摘要或正文 hash。命中的规则与投递结果字段同样不得包含正文或正文派生值。

#### Scenario: 消息正文参与规则匹配后记录去重
- **WHEN** 一条未读消息的正文在内存中用于 notification rule 匹配并触发推送尝试
- **THEN** 已推送记忆记录 MUST NOT 包含该消息正文或由正文派生的持久字段，命中的规则与投递结果字段仅记录规则标识与投递状态

## ADDED Requirements

### Requirement: 推送记录可按账号查询
系统 SHALL 提供按账号分页查询推送记录的能力，返回该账号已触发推送尝试的消息记录，按 `notifiedAt` 倒序排列，每条记录包含时间、来源 chat、messageId、命中的规则与各投递通道的投递结果，供账号详情页的推送记录标签展示。

#### Scenario: 查询某账号的推送记录
- **WHEN** 管理员在账号详情页的推送记录标签中查看某账号的推送记录
- **THEN** 系统 MUST 返回该账号的推送记录列表，按推送时间倒序，包含命中规则与投递结果，且不含消息正文

#### Scenario: 推送记录按账号隔离
- **WHEN** 管理员查询账号 A 的推送记录
- **THEN** 系统 MUST NOT 返回账号 B 的推送记录
