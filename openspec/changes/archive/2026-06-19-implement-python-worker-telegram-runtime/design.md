## Context

`telegram-python-subprocess-runtime` 目前已经提供 Java 侧运行时边界：每个 Telegram 账号一个 Python worker 子进程，worker 源码随 jar 内置并抽取到 `telegram.client.data-dir/runtime/python-worker`，Java 与 worker 通过 stdin/stdout JSON Lines 通信。当前 bundled worker 只根据命令返回模拟状态，不能真实连接 Telegram。

本变更的核心约束是：上层 control server 继续只依赖 `TelegramAccountSessionManager`，部署仍保持一个 jar 加一个 data dir；系统可以要求预安装 Python 和 Python 依赖，但项目内置 worker 源码不需要单独部署。

## Goals / Non-Goals

**Goals:**

- 用真实 Python Telegram client 替换占位 worker，实现个人账号登录、session 复用和新消息监听。
- 保持现有 JSON Lines 协议的基本形态，按需补充字段而不改变 Java 上层接口。
- 每个账号使用独立 `dataDir`，保证 session 文件隔离并支持服务重启后复用登录状态。
- 支持启动时代理选择和代理更新后的受控重连。
- 将 Telegram 消息转换为现有 `TelegramMessageEvent` 所需字段，并继续遵守消息正文不持久化边界。
- 提供清晰的依赖、授权、代理和连接错误诊断，便于真实功能测试。

**Non-Goals:**

- 不在本变更中实现 TDLight runtime。
- 不实现 Telegram 历史消息同步、消息补偿扫描或完整富媒体解析。
- 不改变 control server 的账号、代理、规则、统计和推送数据库模型。
- 不内置 Python 解释器或自动安装 Python 包。
- 不支持同一个 worker 进程管理多个账号；继续保持每账号一个进程。

## Decisions

### 1. 首个真实 worker 继续采用 Pyrogram

当前 Java 依赖检查已经要求 `pyrogram` 和 `tgcrypto`，README 也按这个方向描述。为降低变更面，真实 worker 先使用 Pyrogram 实现登录、session 和消息监听。

备选方案是切换到 Telethon。Telethon 也能满足真实连接需求，但会同步影响依赖检查、文档、异常映射和测试预期。该选择可以留作后续替换，因为 Java 侧只关心 worker 协议。

### 2. worker 内部维护显式授权状态机

worker 保存以下运行时状态：

- `account_id`
- `client`
- `phone_number`
- `phone_code_hash`
- `active_proxy_id`
- `authorization_state`
- `data_dir`
- `started`

状态转换由命令驱动：

```text
start
  ├─ existing session valid ─────────▶ READY
  ├─ no phone number ────────────────▶ WAIT_PHONE
  └─ phone number present, code sent ─▶ WAIT_CODE

submit_phone ─ code sent ────────────▶ WAIT_CODE
submit_code
  ├─ authorized ─────────────────────▶ READY
  ├─ password required ──────────────▶ WAIT_PASSWORD
  └─ invalid code ───────────────────▶ WAIT_CODE + errorMessage
submit_password
  ├─ authorized ─────────────────────▶ READY
  └─ invalid password ───────────────▶ WAIT_PASSWORD + errorMessage
stop ────────────────────────────────▶ LOGGED_OUT
```

Java 可以继续在发送命令后返回乐观状态，但最终状态以 worker 回传的 `status` 事件为准，并由现有 status listener 持久化。

### 3. session 文件只写入账号 data dir

Pyrogram `Client` 使用账号级目录作为工作目录，session 名称使用稳定值，例如 `telegram-account`。Java 下发的 `dataDir` 指向 `telegram.client.data-dir/accounts/{accountId}`，worker 启动时创建目录并把所有 Telegram session 文件放在其中。

这样可以满足一个 jar 部署下的数据归集要求，也方便用户通过删除账号目录手动清理损坏 session。

### 4. 代理采用启动配置，热切换通过重启 worker 完成

Java 侧已在 `updateProxies` 中重启账号 worker 并重新调用 `start`。worker 只需要在 `start` 时选择 Java 下发的第一个 enabled proxy，并映射为 Pyrogram proxy 配置：

- `SOCKS5` → `socks5`
- `HTTP` → `http`
- `HTTPS` → 优先按 `http` 代理处理，若客户端库不支持 HTTPS CONNECT 的独立 scheme，则返回可诊断错误

worker 的 status 事件只回传 `activeProxyId`，错误消息不得包含代理密码。

### 5. stdout 只传协议事件，stderr 只传诊断日志

Java 已经按 stdout 解析 JSON Lines。worker 必须保证 stdout 只输出 JSON 事件：

- `status`
- `message`
- `error`

普通日志写 stderr，并且不得包含验证码、二步密码、代理密码或消息正文。Python 未捕获异常需要被转换为 `error` 事件或写入不含敏感字段的 stderr。

### 6. 消息监听只做轻量归一化

worker 在 `READY` 后注册新消息 handler。收到新消息时提取：

- `accountId`
- `chatId`
- `chatTitle`
- `chatType`
- `senderId`
- `senderName`
- `senderUsername`
- `receivedAt`
- `text`

文本来源优先使用 message text/caption。无法提取文本的消息可以发送空文本事件或跳过；初期选择发送空文本事件，避免用户误以为监听断开，同时让规则层自然不匹配空文本。

### 7. 测试分层，真实 Telegram 测试作为手动验收

自动化测试不依赖真实 Telegram 账号。测试分三层：

- Python 纯单元测试：代理映射、状态事件格式、错误脱敏、消息归一化 helper。
- Java runtime 测试：worker 资源抽取、依赖缺失诊断、stdout 事件解析、状态 listener 和 message listener。
- 手动验收：使用真实 `api_id`、`api_hash`、手机号、验证码和可选代理完成登录，向账号发送消息并验证规则触发。

真实 Telegram 网络测试容易受账号安全策略、验证码、代理质量和网络环境影响，不作为默认 `mvn test` 的强制项。

## Risks / Trade-offs

- [Risk] Pyrogram 的授权异常和 Telegram 安全策略可能导致状态分支比现有接口更复杂。→ Mitigation：先映射到现有 `WAIT_PHONE`、`WAIT_CODE`、`WAIT_PASSWORD`、`READY`、`ERROR`，详细原因放入 `errorMessage`。
- [Risk] Java 发送命令后立即返回的状态可能短暂领先于 worker 真实状态。→ Mitigation：以 worker status 事件作为最终状态，control server 已通过 status listener 持久化异步状态。
- [Risk] 代理热切换需要重启 worker，可能短暂漏收消息。→ Mitigation：本阶段接受重连语义，并在 UI 状态中暴露连接错误；后续再评估无损热切换。
- [Risk] 日志或错误消息泄漏敏感数据。→ Mitigation：集中封装错误脱敏函数，禁止日志打印命令原文、消息正文、验证码、密码和代理密码。
- [Risk] Python 依赖版本差异导致运行行为不一致。→ Mitigation：README 给出推荐安装命令和版本检查方式，Java 启动账号时继续给出缺失依赖诊断。
- [Risk] 真实 Telegram 测试无法在 CI 稳定执行。→ Mitigation：默认测试使用 fake worker 和 helper 单元测试，真实账号测试写入手动验收步骤。

## Migration Plan

1. 保留现有 Java 配置和模块边界，替换 bundled `worker.py` 的占位实现。
2. 增加 worker 内部 helper 模块，用于代理映射、状态事件、错误脱敏和消息归一化。
3. 更新 Java 侧依赖检查和 README，明确 Python 依赖、真实模式配置和手动验收流程。
4. 增加自动化测试，覆盖不需要真实 Telegram 网络的协议和状态行为。
5. 以 `telegram.client.mode=PYTHON_SUBPROCESS`、真实 `api-id`、`api-hash` 和临时 data dir 做手动功能测试。

回滚方式：将 `telegram.client.mode` 改回 `MEMORY`，或恢复上一版 placeholder worker；账号数据库 schema 不需要回滚。真实 worker 产生的 session 文件位于 data dir，可以按账号目录手动清理。

## Open Questions

- `HTTPS` 代理在 Pyrogram 当前版本中的最佳映射需要实现时确认；若不支持，应明确返回配置错误。
- 非文本消息初期发送空文本事件还是跳过，规格阶段需要固定为可测试行为。
- 是否需要增加显式“清除 Telegram session”API，当前设计暂不纳入本变更。
