## Context

当前系统已经有账号级 `scanFrequency` 和 `unreadAgeThreshold` 配置，但 Python worker 的 `scan` 命令尚未承担实际未读扫描职责，实时消息监听又依赖 Pyrogram 更新分发长期运行。用户的目标是“指定会话中仍然未读且超过阈值的消息才触发通知”，这更适合由 Java 周期性触发未读状态扫描，而不是由 Python worker 维护长期监听与业务过滤状态。

现有边界需要保持清晰：`telegram-python-subprocess-runtime` 负责 Telegram 连接和协议输出，`telegram-notifier-control-server` 负责账号配置、规则评估、推送、统计和 SQLite 持久化。Telegram 消息正文仍然只允许作为瞬时事件数据进入内存处理，不得进入已推送记录、统计表或持久日志。

## Goals / Non-Goals

**Goals:**

- 由 Java 控制端按账号扫描频率调度未读扫描，并在每次 `scan` 命令中传入本次要检查的 `chatIds`。
- Python worker 只采集这些 `chatIds` 当前仍未读的 Telegram 消息，并通过现有 JSON Lines 协议返回 `message` envelope 和 scan 完成 `reply`。
- 扩展规范化消息事件，加入稳定的 Telegram `messageId`，用于 Java 侧持久化去重。
- Java 控制端基于账号 `unreadAgeThreshold`、notification rules 和已推送记忆决定是否推送。
- 持久化已推送消息元数据，避免同一条仍未读消息在多次 scan 中重复推送。
- 第一版通过配置指定每个账号的扫描 `chatIds`，为后续数据库/控制台动态配置预留协议和服务边界。

**Non-Goals:**

- 不修复或依赖 Python worker 的实时 `MessageHandler` 长期监听能力。
- 不让 Python worker 执行通知规则、未读年龄阈值判断、推送决策或已推送记忆。
- 不在第一版实现控制台动态维护 monitored chats。
- 不实现可靠投递队列、重试退避或按 channel 粒度的投递幂等。
- 不持久化 Telegram 消息正文。

## Decisions

### 1. scan 请求携带扫描范围，而不是 worker 启动时绑定 chatIds

Java starter API 将从 `scan(long accountId)` 演进为携带扫描上下文的形式，例如 `scan(TelegramScanRequest request)`。请求至少包含 `accountId`、`chatIds`，并可预留 `maxMessagesPerChat` 等采集参数。Python worker 的 `start` 命令仍只负责账号连接和授权，不接收 monitored chat 配置。

**理由：** chatId 属于控制端业务配置。将它放入每次 scan 可以让后续动态配置只影响 Java 下一次请求，不需要重启 worker 或改变 worker 生命周期。

**替代方案：** 在 worker 启动时传入 chatIds。该方案实现简单，但动态调整困难，也会让 Python 保存不属于自身职责的业务配置。

### 2. Python worker 只返回未读消息，不做业务过滤

Python `scan` 处理流程限定为：按输入 `chatIds` 使用 Pyrogram 高层 `get_dialogs()` 检查当前 dialog unread 状态；对目标会话中仍有未读消息的 dialog，调用 `get_chat_history(chat_id, limit=1)` 获取最新消息作为提醒候选，归一化为 `message` envelope，并输出 scan 完成 `reply`。Python 不接收也不使用 `unreadAgeThreshold`，不执行规则匹配，不记忆是否已推送。实时 `MessageHandler` 在命令驱动 scan 模式下不注册，避免 live update 绕过 Java 配置的 scan 范围。

**理由：** 未读年龄阈值、规则、推送和去重都依赖 Java 控制端配置与数据库状态。把这些逻辑放在 Java 中可以保持单一事实来源，也便于后续前端配置和统计一致性。

**替代方案：** Python 直接判断阈值并只返回应通知消息。该方案减少 Java 处理量，但会把业务规则下沉到 worker，导致配置动态化和测试边界变复杂。

### 3. 事件模型增加 messageId

`TelegramMessageEvent` 和 Python `message` envelope `content` 增加 `messageId`。Java 侧使用 `(accountId, chatId, messageId)` 作为一条 Telegram 消息的稳定身份。

**理由：** 仅靠 `receivedAt`、`text` 或 sender 信息无法可靠去重，也不应将消息正文参与持久化去重。Telegram message id 是最直接的幂等键。

**替代方案：** 使用 `chatId + receivedAt` 或消息正文 hash。该方案可能碰撞，且正文 hash 仍然扩大敏感内容处理范围。

### 4. 已推送记忆放在 Java SQLite

新增表保存已推送消息元数据，建议结构为：

```sql
CREATE TABLE notified_telegram_messages (
    account_id INTEGER NOT NULL,
    chat_id INTEGER NOT NULL,
    message_id INTEGER NOT NULL,
    notified_at TEXT NOT NULL,
    PRIMARY KEY (account_id, chat_id, message_id)
);
```

第一版按消息级别去重：同一条 Telegram 消息只提醒一次，不按 rule/channel 细分。记录不得包含消息正文。

**理由：** Java 控制端拥有规则、推送结果、数据库和隐私边界，因此也应拥有幂等记忆。持久化后服务重启不会重复提醒同一条仍未读消息。

**替代方案：** Python 内存记忆已返回或已推送消息。该方案无法跨重启保留，而且 Python 无法知道 Java 是否实际推送。

### 5. 去重与阈值应在规则处理前执行

Java 收到 unread message event 后，先检查 `(accountId, chatId, messageId)` 是否已记录，再判断 `now - receivedAt >= unreadAgeThreshold`。只有通过这些基础条件的事件才进入 notification rules 和统计链路。

**理由：** scan 会反复返回同一条仍未读消息。如果先统计或规则评估，会造成重复统计、重复 rule hit 或重复推送尝试。

**替代方案：** 所有 unread event 都进入现有规则服务，再在发送前去重。该方案会污染消息和规则统计。

### 6. 推送尝试后的记忆语义

第一版采用“匹配规则并尝试推送后即记录已推送”的语义。若一条消息匹配至少一个规则并触发发送尝试，则记录 `(accountId, chatId, messageId)`；如果没有任何规则匹配，则不记录，后续 scan 仍可在规则变更后处理该消息。

**理由：** 这可以避免 push channel 临时失败导致每个扫描周期重复骚扰和重复统计。系统当前不是可靠投递队列，优先保证不重复提醒。

**替代方案：** 只有至少一个 channel 成功后才记录。该方案有补偿能力，但会在 channel 长期失败时持续重试并放大噪音。

### 7. 第一版配置化 monitored chatIds

新增配置属性表达每个账号要扫描的 chatIds。设计上优先支持多账号映射，例如按 account id 配置列表；如果账号未配置 chatIds，则跳过该账号的未读 scan。

**理由：** 第一版不引入新的管理表和前端，降低实现范围；协议已经支持每次 scan 携带 chatIds，后续迁移到数据库动态配置时无需改变 Python worker。

**替代方案：** 立即实现数据库和控制台管理。该方案用户体验更完整，但会扩大变更范围，分散当前核心 scan/去重逻辑。

## Risks / Trade-offs

- **[Risk] live update handler 可能绕过 configured chatIds 直接把其他会话的新消息送入 Java。** → 命令驱动 scan 模式下不注册实时 `MessageHandler`，消息入口统一由 Java 定时 scan 控制。
- **[Risk] Python 返回未读消息正文会扩大敏感数据流。** → 保持“不持久化正文”约束；默认通知模板不需要正文，测试覆盖已推送记录和统计表不包含正文。
- **[Risk] scan 频率过高或 chatIds 过多会增加 Telegram API 调用压力。** → 使用账号 `scanFrequency` 控制调度；第一版配置的 chatIds 数量应保持较小；必要时增加 `maxMessagesPerChat` 和 scan reply 统计便于观察。
- **[Risk] 推送失败后记录已推送会丢失自动补偿机会。** → 第一版接受该权衡，避免重复提醒；后续若需要可靠投递，可引入投递状态和 retry/backoff。
- **[Risk] 规则变更后，已经记录的消息不会因新规则再次通知。** → 第一版以“消息只提醒一次”为幂等语义；没有匹配任何规则的消息不记录，仍可被后续规则变更捕获。
- **[Risk] 服务启动时历史未读消息可能被扫描并在超过阈值后提醒。** → 这是符合“仍未读且超过阈值提醒”的语义；如果后续需要“只提醒启动后新产生的未读”，可增加基线 cursor 策略。

## Migration Plan

1. 添加 Flyway migration 创建已推送消息记忆表，不迁移消息正文。
2. 扩展事件模型和协议解析，使 `messageId` 成为 Python message envelope 与 Java `TelegramMessageEvent` 的字段。
3. 扩展 starter scan API 和 Python manager，使 Java 能发送带 `chatIds` 的 scan 命令。
4. 新增控制端配置属性和扫描调度器，根据账号启用状态、授权状态、scan frequency 与 configured chatIds 调用 scan。
5. 在规则处理入口前加入已推送记忆和 unread age threshold 判断。
6. 实现 Python `scan` 的未读采集和 scan reply 输出。
7. 补充单元与集成测试，验证重复 scan 不重复推送、不持久化正文、阈值未到不推送、规则不匹配不记录。

回滚策略：代码回滚后新增表可保留但不再使用；由于表不存正文且不参与旧逻辑，保留不会影响旧版本运行。

## Open Questions

- 第一版 Python unread scan 使用高层 `get_dialogs()` 过滤还是直接使用 raw API 定向查询 chatIds，需要在实现阶段根据 Pyrogram 能力确认。
- 配置属性的最终命名需要与现有 `telegram-notifier` / `telegram.client` 配置层次保持一致。
- `maxMessagesPerChat` 第一版是否暴露为配置，还是使用固定默认值，需要在实现前确认。