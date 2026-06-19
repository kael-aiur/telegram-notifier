## ADDED Requirements

### Requirement: Worker 启动配置
Python Telegram worker SHALL 在收到 `start` 命令时使用命令中的 `accountId`、`apiId`、`apiHash`、`dataDir`、`phoneNumber` 和 `proxies` 初始化真实 Telegram client。Worker MUST 将所有账号运行时文件写入命令指定的 `dataDir`，不得写入项目目录、当前工作目录之外的隐式位置或系统临时目录。

#### Scenario: 配置完整时初始化真实客户端
- **WHEN** worker 收到包含有效 `apiId`、`apiHash` 和可写 `dataDir` 的 `start` 命令
- **THEN** worker MUST 创建账号数据目录并使用该目录初始化真实 Telegram client

#### Scenario: 数据目录不可写
- **WHEN** worker 收到 `dataDir` 不可创建或不可写的 `start` 命令
- **THEN** worker MUST 输出 `error` 或带 `errorMessage` 的 `status` 事件，且不得创建真实 Telegram 连接

### Requirement: Session 持久化
Python Telegram worker SHALL 为每个账号使用隔离的 Telegram session 文件，并在服务或 worker 重启后复用已有有效 session。Session 文件 MUST 位于账号 `dataDir` 下。

#### Scenario: 已有有效 session
- **WHEN** worker 启动时账号 `dataDir` 中存在有效 Telegram session
- **THEN** worker MUST 复用该 session 并输出 `READY` 状态

#### Scenario: 无有效 session 且无手机号
- **WHEN** worker 启动时没有有效 Telegram session 且 `phoneNumber` 为空
- **THEN** worker MUST 输出 `WAIT_PHONE` 状态

#### Scenario: 无有效 session 且有手机号
- **WHEN** worker 启动时没有有效 Telegram session 且 `phoneNumber` 非空
- **THEN** worker MUST 向 Telegram 请求验证码并输出 `WAIT_CODE` 状态

### Requirement: 授权命令处理
Python Telegram worker SHALL 支持 `submit_phone`、`submit_code` 和 `submit_password` 命令，并将 Telegram 授权结果映射为现有 `AuthorizationState` 名称。授权输入错误 MUST 保留可继续输入的状态，并通过 `errorMessage` 暴露可读诊断。

#### Scenario: 提交手机号成功
- **WHEN** worker 处于 `WAIT_PHONE` 状态并收到有效 `submit_phone` 命令
- **THEN** worker MUST 向 Telegram 请求验证码并输出 `WAIT_CODE` 状态

#### Scenario: 提交验证码后授权成功
- **WHEN** worker 处于 `WAIT_CODE` 状态并收到 Telegram 接受的 `submit_code` 命令
- **THEN** worker MUST 输出 `READY` 状态并开始监听新消息

#### Scenario: 提交验证码后需要二步验证
- **WHEN** worker 处于 `WAIT_CODE` 状态并收到触发二步验证的 `submit_code` 命令
- **THEN** worker MUST 输出 `WAIT_PASSWORD` 状态

#### Scenario: 验证码错误
- **WHEN** worker 处于 `WAIT_CODE` 状态并收到 Telegram 拒绝的 `submit_code` 命令
- **THEN** worker MUST 保持或重新输出 `WAIT_CODE` 状态，并包含不泄漏验证码的 `errorMessage`

#### Scenario: 提交二步验证密码成功
- **WHEN** worker 处于 `WAIT_PASSWORD` 状态并收到 Telegram 接受的 `submit_password` 命令
- **THEN** worker MUST 输出 `READY` 状态并开始监听新消息

#### Scenario: 二步验证密码错误
- **WHEN** worker 处于 `WAIT_PASSWORD` 状态并收到 Telegram 拒绝的 `submit_password` 命令
- **THEN** worker MUST 保持或重新输出 `WAIT_PASSWORD` 状态，并包含不泄漏密码的 `errorMessage`

### Requirement: 代理映射与活动代理
Python Telegram worker SHALL 在 `start` 时按 Java 下发的代理列表顺序选择第一个可用代理，并将 HTTP、HTTPS 和 SOCKS5 代理映射到 Telegram client 支持的代理配置。Worker MUST 在状态事件中回传实际使用的 `activeProxyId`；代理密码不得出现在 stdout、stderr 或错误消息中。

#### Scenario: 使用第一个代理
- **WHEN** `start` 命令包含一个或多个代理
- **THEN** worker MUST 优先使用列表中的第一个代理连接 Telegram，并在状态事件中回传该代理的 `activeProxyId`

#### Scenario: 无代理启动
- **WHEN** `start` 命令中的 `proxies` 为空
- **THEN** worker MUST 使用直连方式连接 Telegram，并输出不含 `activeProxyId` 的状态事件

#### Scenario: 代理配置不受支持
- **WHEN** worker 收到 Telegram client 不支持的代理协议或无效代理配置
- **THEN** worker MUST 输出连接错误诊断，且错误内容不得包含代理密码

### Requirement: JSON Lines 协议事件
Python Telegram worker SHALL 只在 stdout 输出 JSON Lines 协议事件，普通诊断日志 MUST 输出到 stderr。Stdout 事件 MUST 至少支持 `status`、`message` 和 `error` 三类。

#### Scenario: 输出状态事件
- **WHEN** worker 授权状态、活动代理或连接错误发生变化
- **THEN** worker MUST 向 stdout 输出一行合法 JSON `status` 事件

#### Scenario: 输出错误事件
- **WHEN** worker 遇到无法归类为授权状态的运行时错误
- **THEN** worker MUST 向 stdout 输出一行合法 JSON `error` 事件

#### Scenario: 输出诊断日志
- **WHEN** worker 需要输出普通诊断日志
- **THEN** worker MUST 将日志写入 stderr，且不得污染 stdout 协议流

### Requirement: 新消息监听
Python Telegram worker SHALL 在账号达到 `READY` 后监听真实 Telegram 新消息，并将消息归一化为 `message` JSON Lines 事件。消息事件 MUST 至少包含 `accountId`、`chatId`、`chatTitle`、`chatType`、`senderId`、`senderName`、`senderUsername`、`receivedAt` 和 `text` 字段。

#### Scenario: 收到文本消息
- **WHEN** 已就绪账号收到新的 Telegram 文本消息
- **THEN** worker MUST 输出包含正文和来源元数据的 `message` 事件

#### Scenario: 收到 caption 消息
- **WHEN** 已就绪账号收到带 caption 的 Telegram 消息
- **THEN** worker MUST 将 caption 作为 `text` 输出到 `message` 事件

#### Scenario: 收到非文本消息
- **WHEN** 已就绪账号收到无法提取 text 或 caption 的 Telegram 消息
- **THEN** worker MUST 输出 `text` 为空字符串的 `message` 事件，且不得导致 worker 退出

### Requirement: 停止与资源释放
Python Telegram worker SHALL 在收到 `stop` 命令或 stdin 关闭时停止 Telegram client、释放事件监听资源并输出 `LOGGED_OUT` 状态。

#### Scenario: 收到停止命令
- **WHEN** worker 收到 `stop` 命令
- **THEN** worker MUST 停止 Telegram client，输出 `LOGGED_OUT` 状态并退出进程

#### Scenario: stdin 关闭
- **WHEN** Java 关闭 worker stdin 或父进程终止导致 stdin 结束
- **THEN** worker MUST 尽力停止 Telegram client 并退出进程

### Requirement: 敏感信息保护
Python Telegram worker and Java subprocess runtime MUST NOT write Telegram message text, verification codes, two-step passwords, proxy passwords, or API hash values to persistent logs, stderr diagnostics, stdout error messages, database records, or status error messages.

#### Scenario: 授权失败诊断
- **WHEN** Telegram 拒绝验证码或二步验证密码
- **THEN** worker MUST 输出不包含用户输入原文的错误诊断

#### Scenario: 代理连接失败诊断
- **WHEN** Telegram client 使用带密码代理连接失败
- **THEN** worker MUST 输出不包含代理密码的错误诊断

#### Scenario: 消息处理异常
- **WHEN** worker 处理 Telegram 消息时发生异常
- **THEN** worker MUST 输出不包含消息正文的错误诊断
