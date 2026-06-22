# 设计：扫描批次通知去重

## 核心不变量

**同一个 `(accountId, chatId)` 的同一批未读消息，至多触发一次推送。**

`pushed` 布尔只控制"是否 remember 整批"，**不能**保证"只推一次"——因为循环里每条匹配规则的消息都会各自推送一次。必须在循环顶部用 `if (pushed) break` 显式截断，做到 first-match-wins（首条命中即停）。

### 真实案例（促成本不变量的 bug）

配置：1 个 chatId、1 条 enabled 规则、4 条未读消息，其中 2 条同时满足"够老 + 匹配规则"。

旧实现（无 break）在一轮 `handleBatch` 内对这 2 条各推一次 → 用户同一秒收到 2 条通知。根因不是多规则，而是**单规则 + 多条匹配消息**：循环没有"已推过就停"的截断。

| 实现 | 4 条未读、2 条匹配 | 结果 |
|------|-------------------|------|
| 旧（无 break） | msg1 推、msg2 推 | 同秒 2 条通知 ❌ |
| first-match-wins | msg1 推、break | 1 条通知，4 条全 remember ✓ |

## 变更点

### 1. NotificationRuleService.handleBatch()

接收同一次扫描中同一个 `(accountId, chatId)` 的所有未读消息，首条命中即停。

```java
void handleBatch(List<TelegramMessage> messages) {
    var pushed = false;
    for (var event : messages) {
        if (pushed) {
            break;                       // 本批已推送，不再推送（核心不变量）
        }
        if (event.messageId() > 0) {
            if (notifiedMessages.isNotified(event) || !isOldEnough(event)) {
                continue;
            }
        }
        var matched = false;
        statistics.incrementMessages(event.accountId());
        for (NotificationRule rule : list().stream()
                .filter(NotificationRule::enabled)
                .filter(rule -> matches(rule.condition(), event))
                .toList()) {
            matched = true;
            statistics.incrementRuleHit(rule.id());
            var content = render(rule, event);
            for (Long channelId : rule.channelIds()) {
                // ... 推送逻辑同 handle() ...
            }
        }
        if (matched) {
            pushed = true;
        }
    }
    if (pushed) {
        for (var event : messages) {
            notifiedMessages.remember(event);
        }
    }
}
```

关键行为：
- **一批至多一次推送**：首条命中即 `break`，后续消息不再推送（但仍被 remember）
- 已通知的消息跳过（不重复统计、不重复推送）
- 未达年龄阈值的消息跳过（不推送、不统计）
- 一旦本批有任何消息触发推送，**整批消息全部 remember**（包括被跳过的、太新的）
- `handle()` 单条方法保留不变

### 2. TelegramUnreadScanScheduler.scanDueAccounts()

将逐条调用改为收集列表后批量调用：

```java
for (Long chatId : chatIds) {
    var messages = sessions.peekUnreadMessages(account.id(), chatId);
    if (!messages.isEmpty()) {
        notifications.handleBatch(messages);
    }
}
```

### 3. 决策点

**① 用哪条消息渲染通知内容？**
`fetch_unread` 返回 `get_chat_history(limit=N)`，Pyrogram 按**新→旧**排序，故 `messages[0]` 是最新消息。first-match-wins 会用"**最新的那条满足（够老 + 匹配）的消息**"渲染。若最新那条太年轻，会跳过它、改用次新的一条。**默认采纳**此语义。

**② 统计计数（incrementMessages）在 break 后停止计数？**
是的。break 后本批剩余消息不再计入消息统计。即统计反映"被评估推送的消息"，而非"全部未读条数"。**默认采纳**；若希望整批都计数，需在截断前单独做一轮统计遍历。

## 不涉及

- DAO 层 / 数据库 schema
- 前端
- Python 子进程
- `handle()` 单条方法（保留兼容）
