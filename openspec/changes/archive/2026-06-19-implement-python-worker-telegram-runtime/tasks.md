## 1. Worker 基础结构

- [x] 1.1 将 bundled `worker.py` 从占位状态机替换为真实 Pyrogram worker 主流程。
- [x] 1.2 增加 worker 内部运行时状态对象，保存 accountId、client、phoneNumber、phoneCodeHash、activeProxyId、authorizationState 和 dataDir。
- [x] 1.3 增加 worker helper 模块，封装 JSON Lines 事件输出、UTC 时间、错误脱敏和安全日志输出。
- [x] 1.4 确保 worker stdout 只输出协议 JSON Lines，普通诊断全部写入 stderr。
- [x] 1.5 确保 worker 启动时创建并只使用 Java 下发的账号 dataDir。

## 2. Pyrogram 客户端与 Session

- [x] 2.1 使用 `apiId`、`apiHash`、账号 dataDir 和稳定 session 名称初始化 Pyrogram Client。
- [x] 2.2 实现已有 session 检测和复用，已有有效 session 时输出 `READY`。
- [x] 2.3 实现无 session 且无手机号时输出 `WAIT_PHONE`。
- [x] 2.4 实现无 session 且已有手机号时请求验证码并输出 `WAIT_CODE`。
- [x] 2.5 捕获 dataDir、session 和 Telegram 连接初始化错误，输出可诊断且脱敏的错误事件。

## 3. 授权流程

- [x] 3.1 实现 `submit_phone` 命令，向 Telegram 请求验证码并保存 phone code hash。
- [x] 3.2 实现 `submit_code` 命令，处理授权成功、需要二步验证和验证码错误。
- [x] 3.3 实现 `submit_password` 命令，处理二步验证成功和密码错误。
- [x] 3.4 将 Telegram 授权异常映射为 `WAIT_PHONE`、`WAIT_CODE`、`WAIT_PASSWORD`、`READY` 或 `ERROR`。
- [x] 3.5 确保验证码、二步验证密码和 apiHash 不进入 stdout、stderr、状态错误或 Java 日志。

## 4. 代理支持

- [x] 4.1 实现 Java proxy payload 到 Pyrogram proxy 配置的映射。
- [x] 4.2 支持 SOCKS5 和 HTTP 代理连接。
- [x] 4.3 确认 HTTPS 代理在 Pyrogram 中的可行映射；不支持时返回明确配置错误。
- [x] 4.4 在状态事件中回传实际使用的 `activeProxyId`。
- [x] 4.5 确保代理密码不进入 stdout、stderr、错误消息或测试断言输出。

## 5. 新消息监听与归一化

- [x] 5.1 在账号达到 `READY` 后注册 Pyrogram 新消息监听器。
- [x] 5.2 将文本消息归一化为 `message` JSON Lines 事件。
- [x] 5.3 将带 caption 的消息使用 caption 作为 `text` 输出。
- [x] 5.4 对无法提取文本的消息输出空字符串 `text`，且不得让 worker 退出。
- [x] 5.5 提取 chat、sender、receivedAt 等字段并对缺失字段提供稳定默认值。
- [x] 5.6 确保消息处理异常不会把消息正文写入日志或错误诊断。

## 6. Java 运行时配套

- [x] 6.1 检查 Java `PythonSubprocessTelegramAccountSessionManager` 对 worker `status`、`message`、`error` 事件的解析是否满足新规格。
- [x] 6.2 必要时扩展 Java 侧错误脱敏，避免 worker stderr 或错误事件泄漏敏感字段。
- [x] 6.3 确认 `updateProxies` 重启 worker 后能保留账号 session 并重新应用代理链。
- [x] 6.4 确认 control server status listener 能持久化 worker 异步回传的授权状态和连接错误。

## 7. 自动化测试

- [x] 7.1 增加 Python worker helper 测试，覆盖代理映射、状态事件格式和错误脱敏。
- [x] 7.2 增加 Python 消息归一化测试，覆盖文本、caption、非文本和缺失 sender/chat 字段。
- [x] 7.3 增加 Java runtime 测试，覆盖 worker 资源抽取和 Python 依赖缺失诊断。
- [x] 7.4 增加 Java runtime 测试，使用 fake worker 验证 status、message、error 事件解析和 listener 分发。
- [x] 7.5 增加或更新 control server 集成测试，确认真实运行时异步状态不会破坏账号状态持久化。
- [x] 7.6 运行 `mvn test`。

## 8. 文档与手动验收

- [x] 8.1 更新 `telegram-python-subprocess-runtime/README.md`，说明真实模式依赖、配置、dataDir 布局和安装指引。
- [x] 8.2 增加真实 Telegram 手动验收步骤：配置 apiId/apiHash、启动账号、提交手机号、验证码、二步密码、发送消息并观察规则触发。
- [x] 8.3 增加代理手动验收步骤：绑定代理、启动账号、切换代理并确认 activeProxyId 和连接状态。
- [x] 8.4 记录已知限制：真实 Telegram 网络测试不进入默认 CI，HTTPS 代理支持以实现验证结果为准。
