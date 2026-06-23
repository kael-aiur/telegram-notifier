## 1. 数据库迁移（V4 Flyway）

- [x] 1.1 创建 `V4__account_detail_redesign.sql`：`telegram_accounts` 加 `monitored_chat_ids_json TEXT`
- [x] 1.2 V4：`notification_rules` 加 `account_id INTEGER`（可空，应用层强制非空）
- [x] 1.3 V4：`notified_telegram_messages` 加 `matched_rule_ids_json TEXT` 与 `delivery_results_json TEXT`
- [x] 1.4 V4：新建 `account_monitoring_logs` 表（id、account_id 外键级联、chat_id、scanned_at、unread_count、notified_count、created_at）
- [x] 1.5 本地验证 V4 迁移可正向执行且与 V1-V3 兼容（空库与存量库各跑一次）

## 2. Model 与 DAO 层

- [x] 2.1 `TelegramAccount` record 增加 `monitoredChatIds` 字段；更新 RowMapper 与写入 SQL 序列化 `Long[]` ↔ `monitored_chat_ids_json`
- [x] 2.2 `NotificationRule` record 增加 `accountId`；`NotificationRuleDao` 增加 `selectByAccountId`，`insert/update` 写入 `account_id`
- [x] 2.3 `NotifiedMessageDao.insert` 扩展为接收命中规则 id 集合与投递结果列表，写入双 JSON 列；新增 `selectByAccountId(accountId, limit, offset)` 分页倒序查询
- [x] 2.4 新建 `AccountMonitoringLog` model 与 `AccountMonitoringLogDao`：`insert`、`selectByAccountId(accountId, limit, offset)`、`deleteOldestBeyond(accountId, keep)`
- [x] 2.5 新建 JSON 序列化工具或在既有工具上扩展（规则 id 列表、投递结果数组），保持与 `condition_json`/`channel_ids_json` 一致的风格

## 3. 通知规则评估与推送记录 Service 改造

- [x] 3.1 `NotificationRuleService.handle/handleBatch` 规则加载由 `selectAll()` 改为按 `event.accountId` 的 `selectByAccountId`
- [x] 3.2 `handle` 在 dispatch 循环中收集每条命中规则的 id 与各通道 `DeliveryResult`，传入 `remember`
- [x] 3.3 `handleBatch` 同样收集，批内合并推送时为每条消息共享同一组投递结果；`remember` 签名扩展为 `(event, matchedRuleIds, deliveryResults)`
- [x] 3.4 `handleBatch` 返回值由 `void` 改为 `int`（被 remember 的消息条数），更新调用方
- [x] 3.5 `NotifiedMessageService.remember` 扩展实现，写入 `matched_rule_ids_json`（仅规则 id）与 `delivery_results_json`（通道投递结果，message 仅 HTTP 异常或 `"sent"`）

## 4. 扫描调度器与监控日志

- [x] 4.1 `TelegramUnreadScanScheduler.scanDueAccounts` 改为从账号记录读取 `monitoredChatIds`，移除对 `UnreadScanProperties.chatIds` 的调用
- [x] 4.2 每个 chatId 的 `peekUnreadMessages` 之后写入一行 `account_monitoring_logs`：`unread_count` = 返回消息数，`notified_count` = `handleBatch` 返回值
- [x] 4.3 监控日志写入后按 `telegram-notifier.monitoring-log.max-per-account`（默认 1000）调用 `deleteOldestBeyond` 清理该账号超限旧记录
- [x] 4.4 新增配置属性类绑定 `max-per-account`，默认值 1000

## 5. 启动数据迁移

- [x] 5.1 新增 `ApplicationRunner`（Flyway 之后执行）：读 `UnreadScanProperties.accounts`，对仍存在的账号在其 `monitored_chat_ids_json` 为空时写入配置 chatIds
- [x] 5.2 启动迁移：将 `account_id IS NULL` 的历史 `notification_rules` 关联到最小账号 id；不存在任何账号则删除该规则
- [x] 5.3 迁移过程输出日志：已迁移 chatIds 的账号、未迁移（账号已删）的项、已归属的历史规则及目标账号

## 6. 后端 API

- [x] 6.1 `TelegramAccountRequest` 扩展 `monitoredChatIds`；`PUT /api/accounts/{id}/scan-settings` 处理 chatIds 持久化；账号响应 DTO 暴露 chatIds
- [x] 6.2 新增 `AccountRuleController`（`/api/accounts/{accountId}/rules` 的 GET/POST/PUT/DELETE）；`NotificationRuleRequest` 增加 `accountId` 校验非空
- [x] 6.3 移除全局 `NotificationRuleController`（`/api/rules`）及其相关 DTO/路由
- [x] 6.4 新增 `GET /api/accounts/{accountId}/notified-messages`（分页 `limit/offset`，倒序），响应含命中规则 id 与投递结果
- [x] 6.5 新增 `GET /api/accounts/{accountId}/monitoring-logs`（分页 `limit/offset`，倒序）
- [x] 6.6 推送记录响应中通过规则 id join 规则名称展示（不暴露条件/模板/正文）

## 7. 前端路由与账号列表

- [x] 7.1 `router/index.js` 新增 `/accounts/:id` 路由指向 `AccountDetail.vue`，移除 `/rules` 路由
- [x] 7.2 `views/Layout.vue` 侧边导航移除「推送规则」入口
- [x] 7.3 `views/Accounts.vue` 操作列精简为「停止」「重新登录」（未就绪账号仅「登录」）；账号名称改为 `<router-link :to>` 跳转详情页；保留「新建账号」弹窗入口

## 8. 前端账号详情页

- [x] 8.1 新建 `views/AccountDetail.vue`，用 `el-tabs` 组织四个标签，默认进入「账号监控」
- [x] 8.2 账号监控标签：展示授权状态、运行状态、激活代理；启停监听按钮调既有 `start`/`stop`；监控日志列表组件（分页）
- [x] 8.3 设置标签：基本信息表单（名称/电话）、代理链编辑、监听会话 chatIds 编辑（动态增删行，随 scan-settings 保存）、该账号推送规则列表 + `RuleDialog`
- [x] 8.4 推送记录标签：列表展示时间、来源 chat、messageId、命中规则名、投递结果（通道/成功失败），不展示正文
- [x] 8.5 账号操作标签：删除账号（复用既有删除与被引用校验提示）

## 9. 前端 API 与组件调整

- [x] 9.1 `api/accounts.js` 增加 scan-settings 提交 `monitoredChatIds`、`listNotifiedMessages(id, params)`、`listMonitoringLogs(id, params)`
- [x] 9.2 `api/rules.js` 改为账号作用域（基于 `/accounts/{accountId}/rules`），移除全局规则调用
- [x] 9.3 `components/RuleDialog.vue` 改为接收 `accountId` prop，提交到账号作用域端点
- [x] 9.4 移除 `views/Rules.vue`；新建监控日志、推送记录列表组件（如 `MonitoringLogList.vue`、`NotifiedMessageList.vue`）

## 10. 测试与构建验证

- [x] 10.1 后端 DAO 单测：chatIds 序列化往返、规则按账号查询/写入 account_id、推送记录双 JSON 列写入与分页查询、监控日志写入与保留清理
- [x] 10.2 后端 Service 单测：规则按账号作用域评估、`remember` 扩展写入命中规则+投递结果、`handleBatch` 返回推送数
- [x] 10.3 后端启动迁移单测：配置 chatIds 迁移（含「仅当为空」幂等与账号已删场景）、历史规则归属最小账号/无账号删除
- [x] 10.4 隐私边界验证：推送记录 `matched_rule_ids_json` 仅含规则 id、`delivery_results_json.message` 不含渲染 content、监控日志无任何正文字段
- [x] 10.5 后端 Controller 单测：scan-settings 含 chatIds、`/accounts/{id}/rules` CRUD、notified-messages、monitoring-logs、全局 `/api/rules` 已移除
- [x] 10.6 前端测试更新（`App.test.js` 等涉及路由/规则的部分）；`npm run build` 产物写入 `src/main/resources/static`
- [x] 10.7 `mvn test` 全量通过
