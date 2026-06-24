# 实现任务：补充账户扫描频率与未读时长设置入口

> 依据：`proposal.md`、`specs/telegram-account-management/spec.md`、`design.md`
> 性质：纯前端补全，后端 API/DB/调度/判定均已就绪。

## 1. 详情页「设置」标签新增扫描参数编辑项

- [x] 1.1 在 `AccountDetail.vue`「设置」标签新增「扫描频率(秒)」「未读时长(秒)」两个 `el-input-number`（`min="1"`、`step-strictly`），双向绑定到 `account.value.scanFrequencySeconds` 与 `account.value.unreadAgeThresholdSeconds`
- [x] 1.2 实现保存：将「扫描频率」「未读时长」与「监听会话」一并通过 `submitScanSettings(id, data)` 提交，整体携带 `account.value` 当前值，避免分两次提交相互覆盖
- [x] 1.3 保存成功后刷新本地账户状态并提示成功；保存失败时给出可读错误，且不更新本地值

## 2. 统一前端术语为「扫描频率 / 未读时长」

- [x] 2.1 将 `AccountDialog.vue` 的 label「扫描间隔(秒)」改为「扫描频率(秒)」、「未读阈值(秒)」改为「未读时长(秒)」
- [x] 2.2 检查详情页与创建对话框两处术语、单位一致（均为「(秒)」）

## 3. 输入校验

- [x] 3.1 详情页与创建对话框提交前校验「扫描频率」「未读时长」为正整数，否则阻止提交并给出可读提示
- [x] 3.2 核对后端 `ValidationSupport.positive(..., 60L)` / `(..., 3600L)` 兜底逻辑仍生效（仅核对，不改后端）

## 4. 构建与端到端验证

- [x] 4.1 在 `telegram-notifier-control-server/src/main/frontend` 运行前端构建，确认通过且静态资源写入 `src/main/resources/static`
- [ ] 4.2 启动应用进入 `#/accounts/:id`「设置」标签，验证：两项可编辑、初始值等于当前账户配置、保存成功提示；将扫描频率调大后观察下次扫描相应延后、将未读时长调小后观察通知按新阈值触发
- [x] 4.3 运行 `openspec validate add-account-scan-settings` 确认 change 仍有效
