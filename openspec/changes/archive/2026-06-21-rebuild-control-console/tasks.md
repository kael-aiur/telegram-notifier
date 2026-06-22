## 1. 前端工程基础与依赖

- [x] 1.1 安装依赖：`vue-router`、`element-plus`、`@element-plus/icons-vue`、`unplugin-vue-components`、`unplugin-auto-import`
- [x] 1.2 配置 `vite.config.js`：按需引入插件、`@` 路径别名
- [x] 1.3 搭建目录结构：`router/`、`api/`、`views/`、`components/`
- [x] 1.4 `main.js` 注册 Vue Router 与 Element Plus
- [x] 1.5 拆分 `api/` 模块：基于现有 `api.js` 按 `auth`/`system`/`accounts`/`proxies`/`channels`/`rules` 组织，统一错误与 401 处理

## 2. 认证与主布局

- [x] 2.1 `Login` 视图：区分 bootstrap-init（首次）与 login（日常）两种状态
- [x] 2.2 路由守卫：未登录跳 `/login`；接口返回 401 清 token 并跳登录
- [x] 2.3 主布局壳 `App.vue`：侧边导航（四菜单）+ 顶栏（标题/退出）+ `<router-view>`

## 3. 后端核实与必要补丁

- [x] 3.1 核实 `updateProxy`/`updateChannel` 对 `password`/`deviceKey` 空值的处理；不支持空值跳过则补，避免清空密钥
- [x] 3.2 核实 `deleteProxy`/`deleteChannel`/`deleteRule` 是否有引用校验；缺失则补（代理←账号代理链、通道←规则 `channelIds`），返回明确 4xx
- [x] 3.3 核实账号 `scanFrequencySeconds`/`unreadAgeThresholdSeconds` 走主资源 PUT 与专用 `/{id}/scan-settings` 的语义差异，确定编辑落点

## 4. 网络代理（CRUD 样板）

- [x] 4.1 `Proxies` 视图：`el-table` 列表（名称/协议/host:port/启用/创建时间）+ 操作列（编辑/删除）
- [x] 4.2 `ProxyDialog` 弹窗：新建/编辑复用；`password` 编辑回填置空 + 占位「留空表示不修改」
- [x] 4.3 删除二次确认（`ElMessageBox.confirm`）+ 被引用时展示后端拒绝信息（`ElMessage.error`）

## 5. 推送通道

- [x] 5.1 `Channels` 视图：表格（名称/类型/启用/创建时间）+ 操作列（测试/编辑/删除）
- [x] 5.2 `ChannelDialog` 弹窗：新建/编辑复用；`deviceKey` 留空表示不修改
- [x] 5.3 测试发送：操作列「测试」按钮 → `POST /channels/{id}/test`，结果用 `ElMessage` 反馈

## 6. 推送规则

- [x] 6.1 `Rules` 视图：表格（名称/来源标签/模板/启用/创建时间）+ 操作列（编辑/删除）
- [x] 6.2 `RuleDialog` 弹窗：名称/启用/来源标签/模板 + 通道多选（从通道列表）
- [x] 6.3 单叶子条件编辑器：`field` 下拉（9 选 1）+ `op` 下拉（contains/equals/regex/in）+ `value` 输入，提交为 `{field, op, value}`

## 7. 账号管理

- [x] 7.1 `Accounts` 视图：表格（名称/电话/授权状态徽章/激活代理/启用/创建时间）+ 操作列（登录/启停/编辑/删除）
- [x] 7.2 `AccountDialog` 弹窗：名称/电话/启用/扫描设置 + 代理链多选
- [x] 7.3 代理链绑定：保存时 `PUT /accounts/{id}/proxies`
- [x] 7.4 启动/停止：操作按钮 → `POST /accounts/{id}/start|stop`，用返回状态刷新
- [x] 7.5 `LoginWizard` 向导组件：`el-steps` + 按 `authorizationState` 显隐输入框，提交 `/accounts/{id}/login/{phone|code|password}` 用返回状态推进，`READY` 关闭、`ERROR` 重试

## 8. 清理与验证

- [x] 8.1 移除旧 `App.vue` 单文件逻辑与 `Statistics` 视图/导航、`/api/statistics` 拉取
- [x] 8.2 前端构建：`vite build` 产物写入 `src/main/resources/static/`
- [x] 8.3 更新/补充 `vitest` 测试（api 模块、LoginWizard 状态推进、各 Dialog 新建/编辑）
- [x] 8.4 端到端走查：四菜单完整 CRUD + 账号登录向导全状态路径 + 删除引用阻止 + 掩码字段留空不改
