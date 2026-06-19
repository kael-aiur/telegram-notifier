## 1. Java 底层 session 抽象

- [x] 1.1 新增 `TelegramSession` 接口，包含 `start(config)`、`send(String str)`、`getPublisher()`、`stop()`、`getStatus()`，并确保接口不依赖 Telegram 业务对象。
- [x] 1.2 新增 `SessionStatus`，只表达 Python 子进程生命周期状态，不复用或映射 `AuthorizationState`。
- [x] 1.3 新增 `TelegramSessionConfig`，承载 Python executable、worker script、working directory、runtime data directory、extra args、environment、timeouts 和代理配置等 runtime 配置。
- [x] 1.4 明确 `TelegramSessionConfig` 不包含验证码、密码、chatId、拉取未读消息参数等业务字段。

## 2. Java subprocess 实现

- [x] 2.1 实现默认 `SubprocessTelegramSession`，负责启动 Python 进程、设置工作目录和环境变量、维护生命周期状态。
- [x] 2.2 实现 `send(String str)`，校验输入为合法 JSON envelope 后按 JSON Lines 写入 Python stdin。
- [x] 2.3 实现 stdout 读取线程，将 Python stdout 中的 JSON envelope 原样发布到 `Flow.Publisher<String>`。
- [x] 2.4 实现 stderr 读取线程，将 stderr 内容脱敏后包装为 `type=log` envelope 并通过 publisher 发布。
- [x] 2.5 实现进程退出监听，正常停止时进入 stopped 状态，异常退出时进入 failed 状态并发布脱敏诊断。
- [x] 2.6 保留或迁移现有 worker 资源解析、资源复制、依赖检查和工作目录准备逻辑，使其服务于 `SubprocessTelegramSession`。

## 3. Java 协议 helper 与业务适配

- [x] 3.1 新增 Java envelope helper/model，用于生成和校验 `id`、`type`、`content`、可选 `replyInputId`。
- [x] 3.2 在业务适配层中生成 `type=input` envelope，并将旧的 `start`、`submit_phone`、`submit_code`、`submit_password`、`scan`、`stop` 命令放入 `content`。
- [x] 3.3 在业务适配层中按 `replyInputId` 等待 `type=reply`，替代当前只等待下一条 status 的 latch 逻辑。
- [x] 3.4 在业务适配层中订阅 `TelegramSession.getPublisher()`，处理 `reply`、`log`、`message` 三类输出。
- [x] 3.5 将无 `replyInputId` 的 `message` 作为实时消息分发给现有消息监听器。
- [x] 3.6 将带 `replyInputId` 的 `message` 作为对应输入产生的消息结果，并以最终 `reply` 作为该输入完成信号。

## 4. Python envelope 协议层

- [x] 4.1 重构 `telegram_worker/protocol.py`，提供读取 `type=input` envelope 的 helper，并校验 `id`、`type`、`content`。
- [x] 4.2 新增 Python `emit_reply`，输出带自身 `id`、`type=reply`、`replyInputId` 和 `content` 的 JSON Lines envelope。
- [x] 4.3 新增 Python `emit_log`，输出 `type=log` envelope，替代普通 stdout 诊断输出。
- [x] 4.4 新增 Python `emit_message`，输出 `type=message` envelope，并支持可选 `replyInputId`。
- [x] 4.5 确保 Python stdout 只输出 JSON Lines envelope，普通诊断不得污染 stdout 协议流。
- [x] 4.6 保持 Python 侧敏感信息脱敏逻辑覆盖 reply、log、message 之外的诊断路径。

## 5. Python worker 业务命令迁移

- [x] 5.1 将 worker 命令分发从旧顶层 `type=start/submit_code/...` 迁移为读取 `input.content` 中的业务命令。
- [x] 5.2 将 Telegram account start 逻辑改为通过 input content 读取 `accountId`、`apiId`、`apiHash`、`dataDir`、`phoneNumber` 等业务参数。
- [x] 5.3 将代理链改为通过 runtime 配置应用到 Telegram client 网络连接，并继续回传 `activeProxyId`。
- [x] 5.4 将授权命令结果输出为 envelope 内容，确保成功、等待下一步、错误诊断都能与输入 `replyInputId` 关联。
- [x] 5.5 将实时新消息输出为无 `replyInputId` 的 `type=message` envelope。
- [x] 5.6 为拉取未读消息场景保留或实现协议路径：输出多条带 `replyInputId` 的 `message`，最后输出同一 `replyInputId` 的 `reply` 表示完成。
- [x] 5.7 将 stop 命令迁移为 input content 业务命令，并在退出前输出匹配 `replyInputId` 的 final reply。

## 6. 测试迁移与新增覆盖

- [x] 6.1 新增 Java `TelegramSession` 单元测试，使用 fake Python worker 验证 start、send、publisher、stop、status。
- [x] 6.2 新增 Java 测试覆盖 stdout JSON envelope 原样发布和 stderr 脱敏后包装为 `type=log` envelope。
- [x] 6.3 新增 Java 测试覆盖 Python 异常退出时 `SessionStatus` 进入 failed，且诊断信息已脱敏。
- [x] 6.4 迁移现有 `PythonSubprocessTelegramAccountSessionManagerTest` fake worker 输出到新 envelope 协议。
- [x] 6.5 新增测试覆盖 `replyInputId` 等待逻辑，确保业务适配层不会把无关输出当作命令响应。
- [x] 6.6 迁移 Python helper 测试，覆盖 `emit_reply`、`emit_log`、`emit_message`、实时 message 无 `replyInputId`、拉取 message 带 `replyInputId`。
- [x] 6.7 保留并更新敏感信息保护测试，确保 API hash、代理密码、验证码、二步验证密码不出现在日志、reply error 或 stderr 包装输出中。

## 7. 文档与清理

- [x] 7.1 更新 `telegram-python-subprocess-runtime/README.md`，删除旧扁平协议示例，补充 envelope 协议、`replyInputId`、实时 message 和拉取 message 示例。
- [x] 7.2 清理 `telegram-python-subprocess-runtime/src/main/resources/telegram-python-worker` 下不应纳入源码资源的 `__pycache__` 文件。
- [x] 7.3 检查模块命名和包结构，确保底层 session、协议 helper、业务 adapter 分层清晰。

## 8. 验证

- [x] 8.1 运行 `mvn test`，确认 Java 测试通过。
- [x] 8.2 运行 Python helper 测试，确认 worker protocol helper 测试通过。
- [x] 8.3 运行 OpenSpec 校验或状态检查，确认 change artifacts 完整且任务可跟踪。
