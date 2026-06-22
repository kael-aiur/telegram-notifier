# 扫描批次通知去重

## 问题

当前 `TelegramUnreadScanScheduler` 每次扫描时，将 `peekUnreadMessages` 返回的未读消息**逐条**喂给 `NotificationRuleService.handle()`。每条消息独立判断：是否已通知、是否达到年龄阈值、是否匹配规则。

当一个 chat 有5条未读消息时，如果其中3条都达到年龄阈值且匹配规则，就会触发3次推送通知。但用户只需收到1次通知——看到通知后打开 Telegram 就能看到所有未读消息。

## 目标

对于同一个 `(accountId, chatId)` 的同一批未读消息：
- 一批至多触发**一次**推送（首条命中即停，即使多条都匹配规则）
- 只要有**任意一条**触发了推送，该批次所有消息都标记为已通知
- 后续扫描周期不再为这批消息推送
- 新到达的消息（不在已通知集合中）正常独立推送

每个 `(accountId, chatId)` 互不干扰。

## 设计

### 核心变更

在 `TelegramUnreadScanScheduler` 中，将逐条调用改为按 `(accountId, chatId)` 收集批次后调用新方法 `handleBatch()`。

```
当前:
  for chatId in chatIds:
      for message in peekUnreadMessages(accountId, chatId):
          notifications.handle(message)   ← 逐条，每条可能触发推送

改为:
  for chatId in chatIds:
      messages = peekUnreadMessages(accountId, chatId)
      notifications.handleBatch(messages)  ← 批量，该 chat 最多触发1次推送
```

### handleBatch 逻辑

```
handleBatch(List<TelegramMessage> messages):
    pushed = false
    for message in messages:
        if pushed: break               ← 一批至多一次推送（核心不变量）
        if isNotified(message):        → 跳过
        if !isOldEnough(message):      → 跳过（但不标记）
        if 匹配规则:
            推送通知
            pushed = true

    if pushed:
        for message in messages:
            remember(message)          ← 全部标记为已通知
```

### 关键行为

| 场景 | 行为 |
|------|------|
| 5条未读，1条触发推送 | 推1次，5条全部记住 |
| 5条未读，3条都触发推送 | 仍只推1次（首条命中即停），5条全部记住 |
| 5条未读，0条触发推送 | 不推，不记住任何消息 |
| 5条已记住，0条新消息 | 全部跳过，不推送 |
| 5条已记住 + 1条新消息 | 新消息独立判断是否推送 |

### 不改动的部分

- `NotificationRuleService.handle()` 单条处理逻辑保留不变（事件驱动场景仍可用）
- `NotifiedMessageDao` 表结构和查询不变
- `unreadAgeThresholdSeconds` 逻辑不变（太新的消息跳过，但若同批次有消息触发推送，也会被记住）

## 涉及文件

| 文件 | 变更 |
|------|------|
| `TelegramUnreadScanScheduler.java` | 收集消息为列表，调用 `handleBatch` |
| `NotificationRuleService.java` | 新增 `handleBatch()` 方法 |
| `TelegramUnreadScanSchedulerTest.java` | 更新测试覆盖批处理逻辑 |

## 不涉及

- 数据库 schema / Flyway 迁移
- 前端
- Python 子进程
- DAO 层
