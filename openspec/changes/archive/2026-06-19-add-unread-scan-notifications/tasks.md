## 1. 事件模型与协议基础

- [x] 1.1 扩展 `TelegramMessageEvent`，新增 `messageId` 字段，并更新所有构造调用、测试数据和模板/条件可访问字段。
- [x] 1.2 扩展 Python `normalize_message`，从 Telegram message 对象读取稳定 `messageId` 并写入 message envelope content。
- [x] 1.3 更新 Java Python worker message 解析逻辑，读取 `content.messageId` 并构造带 messageId 的规范化事件。
- [x] 1.4 更新 Python/Java 协议测试，覆盖 scan/live message envelope 均包含 `messageId`。

## 2. scan API 与未读采集

- [x] 2.1 新增或扩展 Java starter scan 请求模型，使 scan 调用可携带 `accountId`、`chatIds` 和必要的采集参数。
- [x] 2.2 更新 `TelegramAccountSessionManager`、内存实现和 Python subprocess manager，使 Java 能发送带 `chatIds` 的 scan command。
- [x] 2.3 实现 Python worker `scan` 命令处理：按输入 `chatIds` 查询当前未读消息，并为返回消息设置匹配的 `replyInputId`。
- [x] 2.4 实现 scan 完成 reply，返回 scanned/unread/emitted 等可观测结果，并保证无未读消息时也输出成功 reply。
- [x] 2.5 增加 Python worker 单元测试，覆盖指定 chat 有未读、无未读、不返回非指定 chat、scan 不执行阈值/规则过滤。

## 3. Java 调度与配置

- [x] 3.1 新增控制端配置属性，用于按账号配置第一版 monitored chatIds。
- [x] 3.2 实现账号未读扫描调度器，根据账号启用状态、授权状态、scanFrequency 和 configured chatIds 周期性发起 scan。
- [x] 3.3 确保未配置 monitored chatIds 的账号不会发起 unread scan，并记录必要的调试日志。
- [x] 3.4 增加调度相关测试，覆盖到期扫描、未到期跳过、账号未 READY 跳过、无 chatIds 跳过。

## 4. 已推送记忆持久化

- [x] 4.1 新增 Flyway migration，创建已推送 Telegram 消息记忆表，主键为 `(account_id, chat_id, message_id)`，并包含 `notified_at`。
- [x] 4.2 实现 Java 仓储/服务，用于查询消息是否已推送和记录已推送消息。
- [x] 4.3 保证已推送记忆不写入 Telegram 消息正文、caption、正文摘要、正文 hash 或渲染后的通知内容。
- [x] 4.4 增加数据库测试，验证主键去重、查询行为和隐私字段约束。

## 5. 规则处理、阈值与去重集成

- [x] 5.1 在 notification rule 处理入口前加入已推送检查，已记忆消息不得进入消息统计、规则统计或推送流程。
- [x] 5.2 在 Java 控制端应用账号 `unreadAgeThreshold`，未达到阈值的 scan 消息不得进入规则评估或已推送记忆。
- [x] 5.3 扩展规则条件和模板渲染上下文，支持 `messageId` 字段。
- [x] 5.4 在匹配规则并完成 push channel 发送尝试后，记录 `(accountId, chatId, messageId)` 为已推送；未匹配规则不得记录。
- [x] 5.5 增加规则集成测试，覆盖未达阈值、已推送跳过、规则未匹配不记忆、匹配后记忆、重复 scan 不重复推送。

## 6. 隐私、回归与文档

- [x] 6.1 更新隐私边界相关测试，验证已推送记录、统计表和持久日志不包含 Telegram 消息正文。
- [x] 6.2 更新默认或示例配置文档，说明如何配置账号 monitored chatIds 以及后续动态配置预留方向。
- [x] 6.3 运行完整 Maven 测试，修复因 `messageId` 事件模型变更导致的所有编译和测试失败。
- [x] 6.4 手动或测试方式验证端到端流程：Python scan 返回未读消息，Java 按阈值和规则推送，后续重复 scan 不再重复推送同一 messageId。
