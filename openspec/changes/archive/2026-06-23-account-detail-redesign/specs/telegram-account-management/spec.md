## MODIFIED Requirements

### Requirement: Account scan settings are configurable
The system SHALL allow each Telegram account to configure scan frequency, unread age threshold, and the set of monitored chatIds to scan for unread messages. Monitored chatIds SHALL be stored as account data and maintained at runtime through the account API; the system MUST NOT rely on a configuration file to supply per-account monitored chatIds.

#### Scenario: Update scan settings
- **WHEN** the administrator updates an account's scan frequency, unread age threshold, or monitored chatIds
- **THEN** future scans use the updated settings

#### Scenario: 监听会话来自账号数据
- **WHEN** 调度器到达某账号的下一次扫描时间
- **THEN** 系统 MUST 从该账号的账号数据中读取 monitored chatIds，而不得从配置文件读取
