## ADDED Requirements

### Requirement: Telegram 客户端配置
系统 SHALL 支持真实 Telegram 客户端运行所需的全局配置，包括 `api_id`、`api_hash`、客户端数据目录和是否启用真实运行时的开关。系统 MUST 在真实运行时启用但关键配置缺失时拒绝启动账号会话，并将可诊断错误写入账号连接状态。

#### Scenario: 缺少 Telegram API 配置
- **WHEN** 管理员在真实 Telegram 运行时启用但未配置 `api_id` 或 `api_hash` 的情况下启动账号
- **THEN** 系统 MUST 不创建真实 Telegram 连接，并将账号状态保持为未就绪且记录连接错误

#### Scenario: 配置完整时启动真实运行时
- **WHEN** 管理员已配置有效的 `api_id`、`api_hash` 和可写客户端数据目录
- **THEN** 系统 MUST 使用这些配置初始化真实 Telegram 客户端运行时

### Requirement: 账号会话生命周期
系统 SHALL 将现有账号 `start`、`stop` 和 `status` API 映射到真实 Telegram 客户端会话生命周期。每个账号 MUST 使用隔离的客户端数据目录或等价隔离机制，避免不同账号之间共享授权状态。

#### Scenario: 启动未授权账号
- **WHEN** 管理员启动一个没有有效本地 Telegram 会话的账号
- **THEN** 系统 MUST 创建该账号的客户端会话并返回需要登录输入的授权状态

#### Scenario: 停止账号
- **WHEN** 管理员停止一个正在运行或等待登录输入的账号
- **THEN** 系统 MUST 关闭该账号的 Telegram 客户端连接并将账号状态更新为 `LOGGED_OUT`

#### Scenario: 查询账号状态
- **WHEN** 管理员查询账号状态
- **THEN** 系统 MUST 返回最近一次真实 Telegram 客户端授权状态、活动代理和连接错误

### Requirement: Telegram 授权流程
系统 SHALL 支持手机号、验证码和二步验证密码输入，并将 Telegram 客户端授权状态映射到现有 `AuthorizationState`。系统 MUST 在授权成功后将账号状态更新为 `READY`。

#### Scenario: 提交手机号
- **WHEN** Telegram 客户端要求手机号且管理员提交手机号
- **THEN** 系统 MUST 将手机号提交给 Telegram 客户端并进入等待验证码或后续授权状态

#### Scenario: 提交验证码
- **WHEN** Telegram 客户端要求验证码且管理员提交验证码
- **THEN** 系统 MUST 将验证码提交给 Telegram 客户端，并根据 Telegram 响应进入 `READY`、`WAIT_PASSWORD` 或错误状态

#### Scenario: 提交二步验证密码
- **WHEN** Telegram 客户端要求二步验证密码且管理员提交密码
- **THEN** 系统 MUST 将密码提交给 Telegram 客户端，并在验证成功后进入 `READY`

#### Scenario: 授权输入错误
- **WHEN** Telegram 拒绝手机号、验证码或二步验证密码
- **THEN** 系统 MUST 保留可继续输入的授权状态，并将错误信息写入账号连接状态

### Requirement: 代理链应用
系统 SHALL 将账号绑定的启用代理按优先级提供给真实 Telegram 客户端。系统 MUST 在启动账号时应用代理链，并在代理绑定更新后刷新运行中会话的代理配置。

#### Scenario: 使用优先级最高的代理启动
- **WHEN** 账号绑定了多个启用代理并启动账号
- **THEN** 系统 MUST 优先使用绑定优先级最高的代理作为活动 Telegram 连接代理

#### Scenario: 无代理启动
- **WHEN** 账号没有绑定启用代理并启动账号
- **THEN** 系统 MUST 使用直连方式启动 Telegram 客户端并将活动代理显示为空

#### Scenario: 更新运行中账号代理
- **WHEN** 管理员更新运行中账号的代理链
- **THEN** 系统 MUST 将新的代理配置应用到该账号会话，并更新活动代理状态或连接错误

### Requirement: 消息事件发布
系统 SHALL 在账号授权就绪后监听 Telegram 新消息，并将可匹配的消息归一化为 `TelegramMessageEvent` 发布给现有规则评估流程。事件 MUST 至少包含账号 ID、聊天来源、发送者、接收时间和消息文本。

#### Scenario: 收到新文本消息
- **WHEN** 已就绪账号收到 Telegram 文本消息
- **THEN** 系统 MUST 发布包含该消息文本和来源元数据的 `TelegramMessageEvent`

#### Scenario: 收到不支持的非文本消息
- **WHEN** 已就绪账号收到当前无法提取文本的 Telegram 消息
- **THEN** 系统 MUST 发布不含正文或正文为空的事件，或跳过该消息且不得导致会话异常退出

### Requirement: 消息隐私边界
系统 MUST 不将 Telegram 消息正文持久化到数据库、统计表或应用日志。消息正文 SHALL 仅在内存事件、规则匹配和规则显式引用正文的模板渲染过程中短暂存在。

#### Scenario: 规则命中后记录统计
- **WHEN** 真实 Telegram 消息触发通知规则
- **THEN** 系统 MUST 只持久化聚合统计和投递结果，不得将消息正文写入规则命中记录、投递记录、统计表或持久日志

#### Scenario: 连接错误日志
- **WHEN** Telegram 客户端发生连接或授权错误
- **THEN** 系统 MUST 记录诊断错误但不得包含 Telegram 消息正文
