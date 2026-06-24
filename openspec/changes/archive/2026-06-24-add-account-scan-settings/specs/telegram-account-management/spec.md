## MODIFIED Requirements

### Requirement: Account scan settings are configurable
The system SHALL allow each Telegram account to configure scan frequency, unread age threshold, and the set of monitored chatIds to scan for unread messages. Monitored chatIds SHALL be stored as account data and maintained at runtime through the account API; the system MUST NOT rely on a configuration file to supply per-account monitored chatIds. 账户详情页的「设置」标签 SHALL 为扫描频率与未读时长提供可编辑入口，使管理员可在账户创建之后继续调整这两项，并随监听会话一并通过 scan-settings API 持久化。

#### Scenario: Update scan settings
- **WHEN** the administrator updates an account's scan frequency, unread age threshold, or monitored chatIds
- **THEN** future scans use the updated settings

#### Scenario: 监听会话来自账号数据
- **WHEN** 调度器到达某账号的下一次扫描时间
- **THEN** 系统 MUST 从该账号的账号数据中读取 monitored chatIds，而不得从配置文件读取

#### Scenario: 详情页设置标签暴露扫描参数编辑入口
- **WHEN** 管理员打开某账户详情页的「设置」标签
- **THEN** 系统 MUST 展示「扫描频率」与「未读时长」两个编辑项，单位为秒，初始值反映该账户当前的 scanFrequencySeconds 与 unreadAgeThresholdSeconds

#### Scenario: 详情页保存扫描参数立即生效
- **WHEN** 管理员在详情页「设置」标签修改「扫描频率」或「未读时长」并提交保存
- **THEN** 系统 MUST 通过 scan-settings API 持久化新值，后续扫描调度与未读年龄判定 MUST 立即使用新值，无需重启服务或重连账号

#### Scenario: 扫描参数输入校验
- **WHEN** 管理员提交的「扫描频率」或「未读时长」不是正整数
- **THEN** 系统 MUST 拒绝保存并给出可读错误提示，且 MUST NOT 更新该账户的扫描参数
