# Tasks：扫描批次通知去重

- [x] 1. NotificationRuleService 新增 `handleBatch(List<TelegramMessage>)` 方法
- [x] 2. TelegramUnreadScanScheduler 改为按 chatId 收集消息后调用 `handleBatch`
- [x] 3. 更新 TelegramUnreadScanSchedulerTest 覆盖批处理逻辑
- [x] 4. 运行测试确认通过
- [x] 5. handleBatch 改为 first-match-wins：循环顶部 `if (pushed) break`，保证一批至多一次推送
- [x] 6. 补充测试：同批多条匹配规则的消息只触发一次推送（复现 4 未读 / 1 规则 / 2 命中场景）
