## Why

账户详情页的「设置」标签页目前只能编辑基本信息、代理链、监听会话和推送规则，缺少**扫描频率**和**未读时长**两个核心调度参数的编辑入口。管理员只能在创建账户时（`AccountDialog.vue`）设置这两项，创建后无法通过 UI 调整。而这两项直接决定未读消息扫描的实际行为：

- **扫描频率**（`scan_frequency_seconds`，默认 60s）：`TelegramUnreadScanScheduler.java:69` 据此判断每个账户是否到达下次扫描时间。
- **未读时长**（`unread_age_threshold_seconds`，默认 3600s）：`Services.java:615-618` 的 `isOldEnough()` 据此判断未读消息是否「够旧」才触发通知。

后端能力（DB 列、Model、DAO、Service、`PUT /{id}/scan-settings` 端点）与前端 `submitScanSettings()` API 均已就绪，仅 `AccountDetail.vue` 设置页缺对应表单项。本次补全这个 UI 入口，使「设置」标签页成为完整的账户调度参数编辑入口。

## What Changes

- 在账户详情页「设置」标签页新增两个编辑项：**扫描频率（秒）** 与 **未读时长（秒）**，归入与「监听会话」同一区域，复用已有的 `PUT /api/accounts/{id}/scan-settings` 端点保存。
- 保存后，下一次扫描调度（`TelegramUnreadScanScheduler`）与未读判定（`Services.isOldEnough()`）立即使用新值，无需重启。
- 统一前端术语：创建对话框（`AccountDialog.vue`）当前用「扫描间隔(秒)/未读阈值(秒)」，详情页设置标签与之保持一致（具体措辞在 specs 阶段确定）。
- 无后端 API 变更、无数据库 schema 变更、无 breaking change。不触及隐私边界（不涉及消息正文）。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `telegram-account-management`：增强「Account scan settings are configurable」requirement——账户详情页「设置」标签页必须为扫描频率与未读时长提供可编辑入口，与监听会话一并经 scan-settings API 持久化，保存后立即对后续扫描与未读判定生效。

## Impact

- **前端**（主要改动）：`telegram-notifier-control-server/src/main/frontend/src/views/AccountDetail.vue`——「设置」标签页新增扫描频率、未读时长表单项与保存逻辑，复用 `src/api/accounts.js` 已有的 `submitScanSettings()`。
- **后端**：无改动。`PUT /api/accounts/{id}/scan-settings`（`Controllers.java`）、`Services.updateScan()`、`TelegramAccountDao.updateScanSettings()`、`TelegramUnreadScanScheduler.java:69`、`Services.java:615-618` 均已支持。
- **数据库**：无 schema 变更（`scan_frequency_seconds`、`unread_age_threshold_seconds` 自 V1 已存在）。
- **隐私边界**：不涉及消息正文，符合既有隐私约束。
- **测试**：前端表单交互测试；后端无回归风险（仅复用既有端点）。
