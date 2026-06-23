## Why

当前账号管理是「扁平列表 + 弹窗」模式：编辑、删除、代理绑定、登录等操作全部挤在列表行操作列与弹窗里，账号的运行时状态（监控日志、推送历史、监听会话）无处集中查看；推送规则是全局资源、与账号脱节，多账号场景下规则归属混乱；监听会话（monitored chatIds）依赖 `application.yml` 配置文件，无法在运行时按账号调整。需要一个以账号为中心的详情视图，把监控、设置、推送记录、操作集中呈现，并把推送规则与监听会话改为账号级资源。

## What Changes

- 账号列表页操作列精简为「停止」「重新登录」两项；点击账号名称打开账号详情页（编辑、删除等操作移入详情页）。
- 新增账号详情页，含四个标签：
  - **账号监控**：展示授权状态、运行状态、激活代理、监控日志；支持启动/停止监听（复用现有 `start`/`stop`，停止监听即停止会话并停止定时扫描）；展示该账号的扫描日志。
  - **设置**：编辑名称、电话、代理链、监听会话（monitored chatIds，运行时动态设置）、推送规则。
  - **推送记录**：查看该账号推送过的消息（时间、来源 chat、messageId、命中规则、投递通道、投递结果），不含消息正文。
  - **账号操作**：删除账号等账号级操作。
- **BREAKING**：监听会话 monitored chatIds 从配置文件迁移到账号数据，废弃 `telegram-notifier.unread-scan.accounts` 配置项。
- **BREAKING**：推送规则从全局资源改为账号关联（每条规则归属一个账号），废弃侧边导航的全局「推送规则」视图，规则在账号详情页「设置」标签内管理。
- 扩展已推送记忆为推送记录：按账号查询，并记录命中规则与各通道投递结果（成功/失败）。
- 新增账号监控日志的持久化与按账号查询能力（账号监控标签的数据源）。

## Capabilities

### New Capabilities
- `account-monitoring-log`：按账号持久化的扫描/监控活动日志，支持在账号监控标签中查看该账号的定时扫描轨迹，不得包含消息正文。

### Modified Capabilities
- `control-console`：账号列表操作列精简为「停止」「重新登录」，点击账号名称进入详情页；新增账号详情页四标签交互；移除侧边导航的全局「推送规则」入口。
- `telegram-account-management`：账号的 monitored chatIds 改为账号级数据（**BREAKING**，废弃配置文件来源），账号实体与 API 支持运行时维护监听会话列表。
- `notification-rules`：规则改为账号关联（**BREAKING**），每条规则归属一个账号，规则的增删改查限定在账号作用域内。
- `unread-message-notification-memory`：在去重记忆基础上扩展为推送记录，支持按账号查询，并记录命中规则与各通道投递结果；隐私边界不变，仍不得存储消息正文或正文派生值。

## Impact

- **前端**：重构 `Accounts.vue`（操作列精简、名称可点击）；新增账号详情视图与 `/accounts/:id` 路由；移除 `Rules.vue` 全局视图与侧边导航入口；`RuleDialog` 改为账号作用域；新增推送记录、监控日志列表组件。
- **后端 schema（Flyway 迁移）**：`telegram_accounts` 增加 monitored chatIds（字段或关联表）；`notification_rules` 增加 `account_id`（**BREAKING**，现有全局规则需归属决策）；`notified_telegram_messages` 扩展命中规则与投递结果字段；新增扫描日志表。
- **后端 API**：账号 monitored chatIds 维护接口；规则改为账号作用域（如 `/api/accounts/{id}/rules`）并废弃全局规则端点；新增 `GET /api/accounts/{id}/notified-messages`（推送记录）、`GET /api/accounts/{id}/monitoring-logs`（监控日志）。
- **调度器**：`TelegramUnreadScanScheduler` 改为从账号数据读取 chatIds，移除对 `UnreadScanProperties` 配置的依赖。
- **配置**：废弃 `telegram-notifier.unread-scan.accounts`（需提供配置→数据库的迁移路径或重建指引）。
- **隐私边界**：所有新增持久化（推送记录扩展字段、监控日志）必须遵守既有隐私约束，不得存储 Telegram 消息正文、caption、正文摘要或正文 hash。
