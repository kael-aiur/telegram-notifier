## MODIFIED Requirements

### Requirement: Vue console manages core resources
Vue SPA SHALL 通过侧边导航提供四个核心资源的已认证管理视图——账号管理、推送通道、网络代理、推送规则——每个视图均通过列表表格与弹窗表单支持完整的增、查、改、删（CRUD）操作。

#### Scenario: 已认证管理员打开控制台
- **WHEN** 管理员已通过认证
- **THEN** SPA 在侧边栏呈现四个资源管理视图的导航入口

#### Scenario: 每个资源支持完整增删改查
- **WHEN** 管理员打开四个资源视图中的任意一个
- **THEN** SPA 以表格列出已有资源，并为每一行提供新建、编辑、删除操作

#### Scenario: 新建与编辑复用弹窗表单
- **WHEN** 管理员新建或编辑某个资源
- **THEN** SPA 打开弹窗表单（编辑时回填现有值），保存时提交到对应的创建或更新端点

### Requirement: Vue console supports Telegram login state interaction
Vue SPA SHALL 通过弹窗内的流程节点 stepper 向导驱动账号授权，并根据后端返回的 `authorizationState` 推进。向导 SHALL 仅渲染当前状态所需的输入框——`WAIT_PHONE`（手机号）、`WAIT_CODE`（验证码）、`WAIT_PASSWORD`（两步密码）——提交至对应的登录端点后，用返回的状态推进节点，直到进入 `READY`；遇到 `ERROR` 时 SHALL 展示错误并允许重试。

#### Scenario: 账号等待验证码
- **WHEN** 账号状态为等待验证码（`WAIT_CODE`）
- **THEN** SPA 为该账号展示验证码输入框，并在提交后推进到下一节点

#### Scenario: 向导到达就绪状态
- **WHEN** 某次登录提交返回 `READY`
- **THEN** SPA 将 stepper 标记为完成并关闭向导

#### Scenario: 登录失败
- **WHEN** 某次登录提交返回 `ERROR` 或后端报错
- **THEN** SPA 展示错误信息并保持向导开启以供重试

## ADDED Requirements

### Requirement: 被引用资源阻止删除
Vue SPA SHALL 阻止删除被其它资源引用的资源——被某账号代理链引用的代理、被某条规则引用的推送通道——方式是呈现后端的拒绝信息，而非静默删除。

#### Scenario: 代理被账号引用
- **WHEN** 管理员尝试删除被至少一个账号绑定的代理
- **THEN** SPA 展示后端拒绝信息且该代理保留

#### Scenario: 通道被规则引用
- **WHEN** 管理员尝试删除被至少一条规则引用的推送通道
- **THEN** SPA 展示后端拒绝信息且该通道保留

### Requirement: 掩码字段编辑时保留原值
Vue SPA SHALL 在编辑弹窗中将被掩码的密钥字段——代理密码、推送通道 `deviceKey`——留空时视为不修改，使掩码占位符 `******` 永不被当作真实值回写。

#### Scenario: 编辑代理但不重新输入密码
- **WHEN** 管理员编辑代理且密码字段留空
- **THEN** SPA 提交更新而不改变已存储的密码

#### Scenario: 编辑通道但不重新输入 deviceKey
- **WHEN** 管理员编辑推送通道且 `deviceKey` 字段留空
- **THEN** SPA 提交更新而不改变已存储的 `deviceKey`
