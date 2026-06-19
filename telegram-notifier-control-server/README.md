# Telegram Notifier Control Server

## 未读消息扫描配置

第一版未读扫描的 monitored chatIds 通过应用配置指定，由 Java 控制端在每次 `scan` 命令中传给 Python worker。Python worker 只采集这些会话当前仍未读的消息；未读年龄阈值、通知规则、推送和已推送去重都在 Java 控制端完成。

示例：

```yaml
telegram-notifier:
  unread-scan:
    scheduler-delay-ms: 10000
    max-messages-per-chat: 20
    accounts:
      1:
        - 123456789
        - -1009876543210
```

说明：

- `accounts` 的 key 是本系统内的 Telegram account id。
- 每个账号的列表值是本次 scan 允许采集的 Telegram chat id。
- 如果账号未配置 chatIds，调度器会跳过该账号的未读扫描。
- `scanFrequencySeconds` 和 `unreadAgeThresholdSeconds` 仍由账号设置控制。
- 后续可将 monitored chatIds 迁移到数据库和控制台动态配置；Python worker 协议不需要改变。

隐私边界：已推送消息记忆只保存 `account_id`、`chat_id`、`message_id` 和 `notified_at`，不得保存 Telegram 消息正文、caption、正文摘要、正文 hash 或渲染后的通知内容。
