## Why

当前控制台前端是一个 319 行的单文件 Vue 组件（`App.vue`），没有路由、没有 UI 组件库，交互全部靠裸 `input`/`window.prompt`。更关键的是：四类核心资源（账号、代理、推送通道、推送规则）**只实现了「新建」，缺失编辑与删除**——而后端的 PUT/DELETE 端点早已就绪、从未被前端调用。这导致控制台既难用又无法维护，CRUD 能力先天残缺。

## What Changes

- 引入 **Vue Router + Element Plus**，将控制台重写为经典管理控制台风格：侧边导航 + 数据表格 + 弹窗表单 + 操作列。
- 建立四个主菜单，**每个均提供完整增删改查（List / Create / Update / Delete）**：
  - 账号管理
  - 推送通道
  - 网络代理
  - 推送规则
- 账号登录改为**弹窗 + 流程节点 stepper 的状态机向导**：根据后端返回的 `authorizationState` 动态展示对应输入框（`WAIT_PHONE`→手机号、`WAIT_CODE`→验证码、`WAIT_PASSWORD`→两步密码、`READY`→成功、`ERROR`→错误重试），每步提交后用返回状态推进节点。
- 表单统一使用弹窗（Modal）；新增与编辑复用同一表单。
- **删除策略**：资源被引用时阻止删除（代理被账号引用、通道被规则引用等）。
- **BREAKING**：移除现有「统计」页的前端入口（后端统计采集逻辑保留，不影响）。
- 规则条件编辑器先做**单叶子形态**（字段下拉 + 操作下拉 + 值输入），数据结构保留对后端 `all/any/not` 树能力的兼容，为未来扩展留口。
- 处理**掩码字段编辑陷阱**：编辑时 `deviceKey`/`password` 留空表示「不修改」，避免把掩码占位符 `******` 当真实值回写。
- 可能伴随必要的后端小补丁（空值跳过、删除引用校验），具体归属见 design。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `control-console`：将笼统的「管理核心资源」requirement 细化为「四类资源各支持完整增删改查」；将「展示授权状态并提供输入」细化为「状态机向导按 `authorizationState` 推进」；新增「被引用资源阻止删除」requirement；移除原 spec 中关于统计视图的表述。

## Impact

- **前端**：`telegram-notifier-control-server/src/main/frontend` 全量重写。新增 `vue-router`、`element-plus` 依赖；`package.json`、`vite.config.js` 随之调整；构建产物仍写入 `src/main/resources/static/`。
- **Spec**：更新 `openspec/specs/control-console/spec.md`。
- **后端**：可能的小补丁（`Controllers`/`Services`）：掩码字段提交空值时跳过更新、删除前引用校验。是否需要取决于现状代码，design 阶段核实。
- **隐私边界**：不变。消息正文仍不出现在任何持久层，模板默认仍不含正文。
- **认证**：不变。仍为 `X-Auth-Token`，401 时跳回登录。
