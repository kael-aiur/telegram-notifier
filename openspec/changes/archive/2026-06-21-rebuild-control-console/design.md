## Context

当前控制台前端是一个 319 行的单文件 `App.vue`：没有路由、没有 UI 组件库，靠裸 `input` 与 `window.prompt` 完成交互，且四类资源（账号、代理、通道、规则）只实现了「新建」，编辑与删除缺失。后端 `/api/accounts`、`/api/proxies`、`/api/channels`、`/api/rules` 的 PUT/DELETE 端点早已就绪，前端从未调用。

本次重做聚焦前端重组件，辅以少量后端补丁。隐私边界（消息正文不入持久层）、认证机制（`X-Auth-Token`）均不变。

## Goals / Non-Goals

**Goals:**
- 四个菜单 × 完整 CRUD 的经典管理控制台（侧边导航 + 表格 + 弹窗表单 + 操作列）。
- 账号登录：弹窗 stepper 状态机向导，按 `authorizationState` 显隐输入框并推进。
- 解决两个既有陷阱：掩码字段（`password`/`deviceKey`）编辑回写、被引用资源删除保护。
- 保持构建产物写入 `src/main/resources/static/`，SPA 非根路由刷新可回退到 index。

**Non-Goals:**
- 不做统计页（移除入口；后端 `StatisticsService` 保留）。
- 不做规则条件的 `all/any/not` 树编辑器（数据结构保留兼容，前端暂只暴露单叶子）。
- 不改动后端业务 API 契约（仅必要补丁）。
- 不引入状态管理库（Pinia）——单用户控制台用组件局部状态足够。
- 不做国际化，界面统一中文。

## Decisions

### 1. 技术栈：Vue Router + Element Plus
- **Why**：Element Plus 是 Vue 后台范式的首选，`el-table`/`el-form`/`el-dialog`/`el-menu`/`el-steps` 开箱即用，契合「经典控制台」诉求；Vue Router 解决刷新、书签、嵌套视图。
- **Alternatives**：Naive UI（更现代但后台范式不如 EP 地道）、纯手写 CSS（工作量数倍且无表格/弹窗等重组件）。
- **包体积**：用 `unplugin-vue-components` + `unplugin-auto-import` 按需引入，控制打包大小。

### 2. 目录结构
```
src/main/frontend/src/
├── main.js            # 注册 router、Element Plus
├── App.vue            # 布局壳：侧边导航 + 顶栏 + <router-view>
├── router/            # 路由表（/login、/accounts、/channels、/proxies、/rules）
├── api/               # 封装现有 api.js，按资源拆模块
├── views/             # Accounts / Channels / Proxies / Rules / Login
└── components/        # 资源表单弹窗、登录向导、布局壳等共享件
```

### 3. CRUD 表单：弹窗 + 新建/编辑复用
每资源一个 `<XxxDialog>` 组件，`props.record` 为 `null` 表示新建、否则编辑回填。新建走 POST、编辑走 PUT `/{id}`，提交成功后 emit 刷新。删除用 `ElMessageBox.confirm` 二次确认。

### 4. 账号登录向导：状态机 stepper
独立 `<LoginWizard :accountId>` 组件，`el-steps` 展示「手机号 → 验证码 → 两步密码 → 完成」节点。
- 打开时 `GET /accounts/{id}/status` 初始化当前状态。
- 状态→输入框映射：`WAIT_PHONE`→手机号、`WAIT_CODE`→验证码、`WAIT_PASSWORD`→密码。
- 提交 `POST /accounts/{id}/login/{phone|code|password}`，用返回的 `status.authorizationState` 刷新本地、推进 stepper；`READY` 标记完成并关闭；`ERROR`/后端报错则展示错误、保持开启供重试。
- 账号卡片的操作列按当前状态决定主操作按钮（未登录→「登录」，已就绪→「重新登录」）。

### 5. 掩码字段：留空即不修改
编辑回填时 `password`/`deviceKey` 置空 + placeholder「留空表示不修改」。提交时若该字段为空则不发送（或后端忽略）。**需后端配合**：update 对空值跳过——见 Open Questions 核实。

### 6. 删除引用校验：后端权威
前端无法完整判断引用关系，故删除保护以后端为准：DELETE 收到 4xx 时用 `ElMessage.error` 展示后端错误信息。实现前需核实 `Services` 的 `delete*` 是否已有引用检查，无则补（代理←账号绑定、通道←规则 channelIds）。

### 7. 规则条件编辑器：单叶子
`el-select`(field: accountId/chatId/chatTitle/chatType/senderId/senderName/senderUsername/messageId/text) + `el-select`(op: contains/equals/regex/in) + `el-input`(value)，提交为 `{field, op, value}`。后端 `all/any/not` 树结构不变，前端暂不暴露嵌套编辑入口。

### 8. 统计页移除
删除 `Statistics` 视图与导航项；移除 `App.vue` 中对 `/api/statistics` 的拉取。后端 `StatisticsService` 与 `/api/statistics` 端点保留（不破坏数据采集）。

## Risks / Trade-offs

- **[EP 包体积增大]** → Vite 按需引入插件控制打包大小。
- **[掩码字段后端未支持空值跳过]** → 实现前先核实 `updateProxy`/`updateChannel` 对 null 的处理；不支持则补空值跳过逻辑，否则会把密钥清空。
- **[删除引用校验缺失导致脏数据]** → 强制后端补引用检查，不依赖前端预判。
- **[登录向导状态与后端不同步]** → 每次提交都用返回状态刷新本地，绝不本地猜测推进。

## Open Questions

1. 后端 update（代理密码 / 通道 deviceKey）当前是否已对 null/空值做跳过？实现前需读 `Services`/`Controllers` 核实，决定是否补后端补丁。
2. 后端 delete（代理 / 通道 / 规则）是否已有引用校验？同上需核实，决定校验逻辑落点。
3. 账号扫描设置（`scanFrequencySeconds`/`unreadAgeThresholdSeconds`）编辑走主资源 PUT 还是专用 `/{id}/scan-settings` 端点？倾向主 PUT 一次带齐，核实两者语义差异后定。
