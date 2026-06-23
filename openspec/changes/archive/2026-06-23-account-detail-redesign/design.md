## Context

当前账号管理是「扁平列表 + 弹窗」模式，推送规则是全局资源，监听会话（monitored chatIds）来自 `application.yml`（`UnreadScanProperties` 的 `Map<Long, List<Long>>`）。proposal 提出「以账号为中心的详情页 + 规则/监听会话账号级化」的整体重构；specs 已落地为 5 个 capability 的需求契约。

本设计解决 HOW：数据模型变更、配置迁移、规则账号作用域改造、推送记录与监控日志的持久化、API 与前端结构。涉及的现状代码先例（来自调研）：

- 关联表先例：`account_proxies(account_id, proxy_id, priority)` 复合主键 + 子资源 Controller `AccountProxyController`（类级 `/api/accounts/{accountId}/proxies`）。
- JSON 列先例：`notification_rules.condition_json`、`channel_ids_json`（SQLite TEXT 存 JSON，`_json` 后缀）。
- 投递结果：`PushChannelService.send()` 返回 `DeliveryResult(boolean success, String message)`，`message` 来自 `RestClient` 异常或 `"sent"`，**不含渲染后的通知正文**。
- 规则评估：`NotificationRuleService.handle/handleBatch` 用 `ruleDao.selectAll()` 全量加载后 filter；`remember(event)` 写入 `notified_telegram_messages`，不接收 rule/result。
- 调度器：`TelegramUnreadScanScheduler.scanDueAccounts()` 行 42-65，每个 chatId 调 `peekUnreadMessages` 后是最自然的监控日志写入点。
- Flyway 最新 V3，下一个 V4；风格为 snake_case、INTEGER 布尔、TEXT 时间戳（ISO-8601）。

约束：SQLite、单用户自托管、隐私边界禁存 Telegram 消息正文/caption/正文 hash。

## Goals / Non-Goals

**Goals:**
- 把 monitored chatIds 从配置文件迁到账号数据，运行时可维护。
- 把推送规则从全局资源改为账号作用域资源（含历史数据迁移）。
- 扩展已推送记忆为可查询的推送记录（含命中规则与投递结果），并新增按账号查询的监控日志。
- 前端从「列表+弹窗」演进为「列表+账号详情页（四标签）」，移除全局规则导航。
- 全程不扩大正文持久化范围。

**Non-Goals:**
- 不改变 Telegram 会话连接/授权/代理 failover 的既有机制（启停监听复用现有 `start`/`stop`，不新增扫描开关字段）。
- 不重构通知合并去重（batch-scan-notification-dedup）的核心逻辑，仅扩展其返回值与写入内容。
- 不引入新的推送通道（仍以 Bark 为主）。
- 不做监控日志/推送记录的全文检索或导出，仅分页列表查询。

## Decisions

### 决策 1：monitored chatIds 用账号 JSON 列，维护并入既有 scan-settings 端点

**选择**：`telegram_accounts` 新增 `monitored_chat_ids_json TEXT` 列，存 `Long[]`；前端通过既有 `PUT /api/accounts/{id}/scan-settings` 提交（请求体扩展 `monitoredChatIds` 字段）。

**Why**：chatIds 无优先级、不引用其他表，与现有 `condition_json`/`channel_ids_json` 同属「值列表」语义，用 JSON 列与项目约定一致，单表读写最简。代理用关联表是因为它有 `priority` 且引用 `proxy_servers`；chatIds 不具备这两点，关联表是过度设计。复用 scan-settings 端点避免新增端点，且 specs 已将 chatIds 归入 scan settings。

**Alternatives**：① 关联表 `account_monitored_chats`（复用 `account_proxies` 模式）——拒绝，因无 priority/外键引用语义，徒增事务复杂度。② 新增独立 `PUT /{id}/chat-ids` 端点——拒绝，与 scan settings 职责重叠。

### 决策 2：配置→数据库启动迁移，迁移后调度器只读 DB

**选择**：新增 `ApplicationRunner` 在启动时读取旧 `UnreadScanProperties.accounts`，对每个**仍存在**的账号，当其 `monitored_chat_ids_json` 为空时写入配置中的 chatIds；已非空则跳过（不覆盖用户已在 UI 设置的值）。迁移完成后输出日志列出已迁移/未迁移（账号已删除）的项。`TelegramUnreadScanScheduler` 改为从账号记录读 chatIds，移除对 `UnreadScanProperties` 的依赖；配置项保留可读（向后兼容）但不再影响调度。

**Why**：单用户自托管、账号数量少，启动一次性迁移简单可靠；「仅当为空才写」保证幂等且不覆盖用户后续 UI 操作。账号记录在每轮 scan 已通过 `accounts.list()` 重新加载，chatIds 作为账号字段随行加载，无需额外缓存。

**Alternatives**：① 直接废弃配置项不做迁移——拒绝，会静默丢失用户现有 chatIds，违背「无感升级」。② 运行时双读（DB 优先、配置兜底）——拒绝，长期留下双源歧义。

### 决策 3：规则账号关联——加列 + 评估作用域 + 子资源 API + 前端内嵌

**选择**：
- `notification_rules` 加 `account_id INTEGER` 列（SQLite 加 NOT NULL 列需默认值，故列本身可空；**由应用层 DAO/Service 强制非空**，迁移保证历史行都有值）。
- V4 迁移后，启动迁移把 `account_id` 为空的历史规则关联到「最小 id 的账号」并记日志；若不存在任何账号则删除该规则。
- DAO 新增 `selectByAccountId(accountId)`；`handle/handleBatch` 用 `event.accountId` 作用域加载规则，替换 `selectAll()`。
- 新增 `AccountRuleController`（`/api/accounts/{accountId}/rules`，CRUD），**移除**全局 `NotificationRuleController`（`/api/rules`）。
- 前端移除 `Rules.vue` 与侧边导航项与 `/rules` 路由；`RuleDialog` 改为接收 `accountId`，在详情页设置标签内调用。

**Why**：规则归属账号是 BREAKING 的核心，子资源路由复用 `AccountProxyController` 先例，评估作用域改造仅替换一处 `list()` 调用。历史规则归属「最小 id 账号」是单用户场景最简且可解释的兜底（启动日志提示用户核对）。

**Alternatives**：① 保留全局规则视图、规则可选绑定账号——拒绝，与「废弃全局视图」「不同账号自行设置」的需求冲突。② 迁移时把历史规则复制到所有账号——拒绝，会产生重复规则、语义不清。③ 强制 DB 层 NOT NULL——拒绝，SQLite 改约束需重建表，成本高于收益，应用层校验足够。

### 决策 4：推送记录扩展——双 JSON 列，去重语义不变，remember 签名扩展

**选择**：`notified_telegram_messages` 加两列：
- `matched_rule_ids_json TEXT`：命中的规则 id 列表（如 `[1,3]`），**只存 id，不存规则名/条件**。
- `delivery_results_json TEXT`：投递结果数组（如 `[{"ruleId":1,"channelId":2,"success":true},{"ruleId":1,"channelId":3,"success":false,"message":"..."}]`），`message` 仅存通道层 HTTP 异常信息或 `"sent"`。

去重主键 `(account_id, chat_id, message_id)` 不变，一条消息一条记录。`NotifiedMessageService.remember()` 签名扩展为接收命中规则 id 集合与投递结果列表；`handle/handleBatch` 在 dispatch 循环中收集每条规则×通道的 `DeliveryResult` 后传入。同 chat 合并推送（batch 去重）时，批内每条消息共享同一组投递结果。

**Why**：JSON 列与项目 `_json` 约定一致；规则只存 id（前端 join 名称展示）规避任何正文泄漏；`DeliveryResult.message` 本就是 HTTP 层异常（不含渲染正文），直接复用安全。去重语义不变保证既有去重测试与行为稳定。

**Alternatives**：① 关联表 `notified_message_deliveries`（一对多）——拒绝，查询主路径是「按账号列明细」，JSON 列更简单且量级可控。② 把渲染后的通知内容存进记录——**明确拒绝**，违反隐私边界（通知内容可能含正文）。

### 决策 5：监控日志——新表 + chatId 粒度写入 + handleBatch 回传推送数 + 按量保留

**选择**：新建 `account_monitoring_logs`：
```sql
id INTEGER PRIMARY KEY AUTOINCREMENT,
account_id INTEGER NOT NULL REFERENCES telegram_accounts(id) ON DELETE CASCADE,
chat_id INTEGER NOT NULL,
scanned_at TEXT NOT NULL,
unread_count INTEGER NOT NULL,
notified_count INTEGER NOT NULL,
created_at TEXT NOT NULL
```
调度器在每个 chatId 的 `peekUnreadMessages` 之后写入一行：`unread_count` = 返回消息数，`notified_count` = `handleBatch` 本次实际推送的消息数。为此将 `handleBatch` 返回值由 `void` 改为 `int`（被 remember 的消息条数）。保留策略：每账号保留最近 N 条（默认 N=1000，可配 `telegram-notifier.monitoring-log.max-per-account`），写入后清理该账号超限的最早记录。

**Why**：chatId 粒度与 `peekUnreadMessages` 的调用边界对齐，是「扫描轨迹」最自然的单位；纯 INSERT（参考 `notified_telegram_messages`）而非聚合 upsert（参考 `delivery_stats`），因为明细查询需要逐条。`handleBatch` 改返回值是获取「实际推送数」的最小改动（其内部已知道 remember 了哪些消息）。按量保留简单可预测，避免时间清理的边界问题。

**Alternatives**：① 监控日志放 `handleBatch` 内部——拒绝，那是规则评估层，越界且粒度错位。② 只记 unread_count 不记 notified_count——拒绝，与 specs「触发推送的消息数」不符。③ 按时间保留（如 7 天）——备选，但按量保留对使用密度不均的账号更稳定，定为默认。

### 决策 6：前端路由与详情页组件结构

**选择**：
- router 新增 `/accounts/:id` → `AccountDetail.vue`；移除 `/rules` 路由与侧边导航项。
- `Accounts.vue` 操作列精简为「停止」「重新登录」（未就绪账号仅「登录」）；账号名称改为 `<router-link>`。
- `AccountDetail.vue` 用 `el-tabs` 四标签：
  - 账号监控：授权/运行状态、激活代理（复用 `GET /api/accounts/{id}` 与代理链端点）、监控日志列表（新组件，调 `/monitoring-logs`）、启停监听按钮（调既有 `start`/`stop`）。
  - 设置：基本信息表单、代理链（复用既有交互）、监听会话 chatIds 编辑（并入 scan-settings 保存）、该账号推送规则列表 + `RuleDialog`（账号作用域）。
  - 推送记录：列表组件调 `/notified-messages`，展示时间/chat/messageId/命中规则（join 名称）/投递结果。
  - 账号操作：删除账号（复用既有删除，含被引用校验）。

**Why**：基本信息、代理、启停、登录、删除的后端 API 均已存在（调研确认），前端是重排与聚合；新增的仅是监控日志/推送记录两个列表与 chatIds/规则的账号作用域编辑。

### 决策 7：隐私边界强化点（贯穿决策 4/5）

明确三处持久化都不得引入正文：
- `matched_rule_ids_json`：只存规则 id，不存名称/条件/模板。
- `delivery_results_json.message`：只存通道 HTTP 异常或 `"sent"`，**不存渲染后的通知 content**。
- `account_monitoring_logs`：只存计数（unread_count/notified_count）与元数据（account_id/chat_id/时间），不存任何消息字段。

## Risks / Trade-offs

- **[历史规则归属错误]** 迁移把全局规则归到「最小 id 账号」可能与用户心理模型不符 → 启动日志明确列出每条规则的归属账号，提示用户到详情页核对/调整；规则可编辑归属。
- **[配置 chatIds 对应账号已删除]** 该 chatIds 无法迁移 → 日志列出未迁移项；不静默丢弃信息。
- **[handleBatch 返回值变更影响调用方]** → 当前仅调度器调用 handleBatch，改动可控；同步更新其测试。
- **[合并推送的投递结果在同批消息间重复存储]** → 单用户量级可接受；换取不破坏 (account,chat,message) 去重语义。
- **[监控日志增长]** → 按量保留（默认 1000/账号）+ 写入后清理；写入与清理同事务或紧邻执行。
- **[规则 condition 中既有 accountId 条件变为冗余]** → 无害，保留兼容，不强制清理。
- **[SQLite account_id 列可空 vs 应用层非空]** → 若绕过应用层直接写库可能产生无主规则；接受该风险（单用户、无其他写入路径），靠 DAO insert 强制。

## Migration Plan

**V4 Flyway 迁移**（一次执行）：
1. `ALTER TABLE telegram_accounts ADD COLUMN monitored_chat_ids_json TEXT;`
2. `ALTER TABLE notification_rules ADD COLUMN account_id INTEGER;`
3. `ALTER TABLE notified_telegram_messages ADD COLUMN matched_rule_ids_json TEXT;`
4. `ALTER TABLE notified_telegram_messages ADD COLUMN delivery_results_json TEXT;`
5. `CREATE TABLE account_monitoring_logs (...);`

**启动迁移**（`ApplicationRunner`，Flyway 之后）：
- 将 `UnreadScanProperties.accounts` 中存在账号的 chatIds 写入 `monitored_chat_ids_json`（仅当为空）。
- 将 `notification_rules.account_id IS NULL` 的行关联到最小账号 id（无账号则删除）。

**部署**：升级即跑 Flyway V4 + 启动迁移，无需手动步骤。**回滚**：单用户自托管，回滚依赖 DB 备份；V4 为纯增量（加列+加表），向下兼容旧代码忽略新列。

## Open Questions

- 监控日志保留上限默认值（暂定 1000/账号）与是否暴露为配置项——暂定可配 `telegram-notifier.monitoring-log.max-per-account`。
- 推送记录/监控日志分页参数形态（`?limit&offset` vs 游标）——暂定 `limit/offset`，与既有列表风格一致。
- 监听会话 chatIds 的前端编辑形态（逗号分隔输入 vs 动态增删行）——暂定动态增删行，体验更清晰，design 不锁定。
