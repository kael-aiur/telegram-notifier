## Why

`telegram-python-subprocess-runtime` 当前把 Python 子进程生命周期、stdin/stdout 协议、命令响应等待、Telegram 授权与消息业务适配混在同一个实现中，导致 Java/Python 交互协议难以扩展，也难以可靠区分命令响应、日志和异步消息。

本变更先聚焦模块内部协议边界，抽出最基础的 Python 子进程交互对象，为后续 Telegram 业务适配和未读消息拉取能力提供稳定的 JSON Lines 通信基础。

## What Changes

- 引入基础 Java 接口 `TelegramSession`，作为 Python 子进程交互对象，仅负责：
  - `start(config)` 启动 Python 进程并应用 runtime 运行配置。
  - `send(String str)` 向 Python stdin 写入 JSON 字符串。
  - `getPublisher()` 以 `java.util.concurrent.Flow.Publisher<String>` 发布 Python 输出。
  - `stop()` 停止 Python 进程。
  - `getStatus()` 返回 Python 子进程生命周期状态。
- 引入 `SessionStatus`，表达 Python 子进程状态，不承载 Telegram 授权状态或业务状态。
- 定义 `TelegramSession` 的启动配置边界：`start(config)` 的 config 面向 Python runtime 正常运行所需配置，至少支持代理配置，并预留未来扩展其他运行配置；不得包含 `submitCode`、`chatId` 等 Telegram 业务命令字段。
- **BREAKING**：重构 Java/Python JSON Lines 协议为统一 envelope。`send(String str)` 输入和 publisher 输出都必须是 JSON 格式，顶层字段包括：
  - `id`
  - `type`
  - `content`
  - 可选 `replyInputId`
- Java -> Python 当前仅定义 `type=input`。
- Python -> Java 当前支持 `type=reply`、`type=log`、`type=message`，并允许未来扩展新的输出类型。
- `reply` 输出必须通过 `replyInputId` 关联某次输入。
- `message` 输出支持两种来源：
  - 实时 Telegram 新消息：不包含 `replyInputId`。
  - 响应某次拉取未读消息 input 的消息：包含 `replyInputId`，并由最终 `reply` 表示该 input 处理完成。
- 保持隐私边界：协议日志、错误和持久化记录不得泄漏 Telegram 消息正文、验证码、二步验证密码、代理密码或 API hash。
- 暂不要求调整 control-server 外部调用方式；本变更优先解决 `telegram-python-subprocess-runtime` 模块内的 Java/Python 交互协议和分层。

## Capabilities

### New Capabilities

- 无。

### Modified Capabilities

- `python-telegram-worker-runtime`: 将现有 Python worker runtime 的 Java/Python 命令与事件协议改为统一 JSON Lines envelope，并补充 `TelegramSession` 子进程交互边界、runtime 配置边界、`replyInputId` 响应关联、`log` 输出和 `message` 输出语义。

## Impact

- 影响 `telegram-python-subprocess-runtime` 的 Java 实现、Python worker 协议 helper、worker 命令处理、模块 README 和测试。
- 现有 fake worker 测试和 Python helper 测试需要迁移到 envelope 协议。
- 旧的扁平命令/事件协议（如 `type=start`、`type=status`、`type=error`）将被模块内部新协议取代。
- 真实 Telegram 连接、授权流程、消息监听和代理使用仍由 Python worker 承担，但其输出必须通过新的 envelope 形态发布。
- 本变更不直接改变 control-server REST API、数据库结构、推送渠道或前端行为。
