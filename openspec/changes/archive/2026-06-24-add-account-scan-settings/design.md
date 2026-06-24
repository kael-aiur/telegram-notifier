## Context

账户详情页 `AccountDetail.vue` 的「设置」标签目前可编辑基本信息、代理链、监听会话、推送规则，但缺少**扫描频率**（`scanFrequencySeconds`，默认 60s）与**未读时长**（`unreadAgeThresholdSeconds`，默认 3600s）两个调度参数的编辑入口。这两个值由后端 `TelegramUnreadScanScheduler.java:69` 与 `Services.java:615-618`（`isOldEnough`）实际消费。后端的 `PUT /api/accounts/{id}/scan-settings` 端点、`Services.updateScan()`、`TelegramAccountDao.updateScanSettings()`、前端 `submitScanSettings()` 均已就绪，且创建对话框 `AccountDialog.vue` 已有这两个字段——仅详情页设置标签缺 UI。

本次为**纯前端补全**：无后端 API 变更、无数据库 schema 变更。

## Goals / Non-Goals

**Goals:**
- 在详情页「设置」标签暴露扫描频率、未读时长编辑入口，复用现有 `PUT /accounts/{id}/scan-settings` 保存并立即生效。
- 统一前端术语为「扫描频率 / 未读时长」，消除创建对话框与详情页的术语不一致。

**Non-Goals:**
- 不改后端 API、DB schema、扫描调度、未读判定逻辑。
- 不改默认值（60s / 3600s）。
- 不引入账户级配置的新维度（全局调度心跳 `scheduler-delay-ms` 仍为全局配置，不下沉为账户级）。
- 不改 `max-messages-per-chat` 等其它全局扫描参数。

## Decisions

1. **复用 `PUT /accounts/{id}/scan-settings`，不新增端点。**
   该端点已同时接受 `scanFrequencySeconds`、`unreadAgeThresholdSeconds`、`monitoredChatIds`，且详情页现有「监听会话」保存已走它。将扫描频率/未读时长并入同一保存调用最自然。
   *备选*：新增 `PUT /scan-frequency` 独立端点——拒绝，徒增端点与请求次数，无收益。

2. **扫描参数与「监听会话」归属同一保存语义。**
   三者同属 scan-settings，保存时整体提交 `account.value` 当前值，避免分两次提交相互覆盖。
   *UI 形态*（独立卡片「扫描设置」或并入「监听会话」卡片）留待 tasks 实现阶段定，不阻塞设计。

3. **术语统一为「扫描频率 / 未读时长」（方案 A）。**
   同步修改 `AccountDialog.vue` 两个 label。贴合需求原话，消除「创建时叫『扫描间隔/未读阈值』、详情页又叫别的」歧义。

4. **单位：秒。**
   label 明示「(秒)」，与字段语义（`*Seconds`）一致。不引入「分钟」等转换层，避免前后端单位歧义。

5. **输入校验：前端 `el-input-number min=1`，后端 `ValidationSupport.positive` 兜底。**
   前端提交前做正整数校验给出可读错误，后端兜底默认值（60/3600）防止脏数据落库。

## Risks / Trade-offs

- **[保存覆盖]** 同一 scan-settings 保存同时携带 `monitoredChatIds`——若「扫描设置」与「监听会话」分两次保存，可能用旧值覆盖对方。→ 缓解：统一以 `account.value` 当前值整体提交，或合并为单一「保存扫描设置」按钮。
- **[术语变更]** 修改 `AccountDialog.vue` label 属既有 UI 文案变更，无功能影响，回归风险极低。
- **[生效延迟]** 扫描频率变短后，受全局调度心跳 `scheduler-delay-ms`（默认 10s）限制，实际最短扫描间隔不低于该心跳。→ 不属本次改动范围，但在 UI 帮助文案中可提示「实际扫描精度受调度心跳影响」。

## Migration Plan

- 无数据迁移、无配置迁移。仅需前端重新构建（`npm run build` → `src/main/resources/static`）并替换静态资源。

## Open Questions

- 「扫描设置」独立成卡片，还是并入「监听会话」卡片？（实现阶段定）
- 是否在编辑项旁加简短说明文案（如「未读时长：消息保持未读多久后触发通知」）？（实现阶段定）
