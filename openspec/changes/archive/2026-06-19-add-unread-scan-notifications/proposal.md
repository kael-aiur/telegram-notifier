## Why

当前 Python worker 的实时消息监听依赖常驻更新分发，但用户的核心需求不是“收到消息立即通知”，而是“指定会话中仍然未读且超过阈值的消息才提醒”。将未读扫描建模为 Java 调度的 scan 命令，可以更准确地表达业务语义，并避免 Python worker 承担通知过滤、推送记忆和动态配置职责。

## What Changes

- Java 控制端按账号扫描频率发起未读扫描，并在每次 `scan` 命令中携带本次要扫描的 `chatIds`。
- 第一版扫描会话列表通过应用配置指定；后续可迁移为数据库/控制台动态配置，worker 协议不需要改变。
- Python worker 不在启动时接收或持久保存监控会话列表；它只按 `scan` 命令中的 `chatIds` 查询 Telegram 当前未读消息，并将未读消息作为 `message` envelope 返回给 Java。
- Python worker 不判断 `unreadAgeThreshold`、不执行 notification rule、不决定是否推送、不记忆已推送消息。
- 规范化 Telegram message event 增加稳定的 Telegram `messageId`，用于 Java 侧去重和持久化记忆。
- Java 控制端负责：
  - 判断未读消息是否超过账号 `unreadAgeThreshold`；
  - 使用现有 notification rules 做业务过滤；
  - 调用 push channels 发送通知；
  - 记录已处理/已推送的 `(accountId, chatId, messageId)`，避免同一条仍未读消息在后续 scan 中重复推送。
- 新增 SQLite 持久化表保存已推送消息元数据，例如 `account_id`、`chat_id`、`message_id`、`notified_at`；不得保存 Telegram 消息正文。
- 保持隐私边界：Telegram 消息正文可在内存中用于规则匹配或模板渲染，但不得写入已推送记录、统计表或持久日志。

## Capabilities

### New Capabilities

- `unread-message-notification-memory`: 定义 Java 控制端对已推送 Telegram 消息的持久化记忆能力，使用 `(accountId, chatId, messageId)` 去重，并保持不存储消息正文的隐私边界。

### Modified Capabilities

- `python-telegram-worker-runtime`: 扩展 Python worker 的 `scan` 输入语义，使其按 Java 提供的 `chatIds` 返回当前未读消息；扩展 `message` envelope 内容包含 `messageId`，并明确 worker 不执行通知业务过滤。
- `telegram-account-management`: 扩展账号扫描行为，使账号的扫描频率和未读年龄阈值由 Java 控制端用于调度与过滤；第一版支持通过配置为账号指定扫描 `chatIds`。
- `notification-rules`: 扩展规则处理输入的消息事件字段，支持带 `messageId` 的未读消息事件，并在规则评估前后与已推送记忆协同避免重复通知。

## Impact

- 影响 Java starter API：需要让 `scan` 能携带扫描上下文，例如账号、本次 `chatIds` 和后续可扩展参数。
- 影响 Python subprocess runtime：需要实现 `scan` 命令的未读消息采集，返回带 `messageId` 的 message envelope，并输出 scan 完成 reply。
- 影响控制服务：需要新增扫描调度、配置属性、未读年龄过滤、已推送消息持久化仓储和 Flyway migration。
- 影响规则/统计链路：重复 scan 返回的同一条未读消息应先经过去重，避免重复推送和重复统计。
- 影响测试：需要覆盖 scan 命令协议、未读消息归一化、Java 阈值过滤、规则匹配、已推送去重和隐私边界。