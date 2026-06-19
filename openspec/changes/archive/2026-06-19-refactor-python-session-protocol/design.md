## Context

`telegram-python-subprocess-runtime` 当前由 `PythonSubprocessTelegramAccountSessionManager` 同时承担业务接口适配、Python 子进程管理、JSON Lines 协议读写、命令响应等待、stdout/stderr 处理、Telegram 授权状态映射和消息事件分发。Python worker 侧也将协议 envelope 与业务事件混在扁平 JSON 中，例如 Java 输入 `type=start`、Python 输出 `type=status/message/error`。

这种结构在当前功能可用，但对后续协议演进不友好：Java 难以可靠判断某个输出是否属于某次输入，实时消息和主动拉取消息没有统一关联机制，日志与协议输出边界也不一致。本设计将先在模块内部建立稳定的 Python 子进程通信层，再由上层业务适配继续处理 Telegram 登录、消息、扫描等业务语义。

## Goals / Non-Goals

**Goals:**

- 引入 `TelegramSession` 作为最基础的 Python 子进程交互接口，只负责进程生命周期、stdin 写入、输出发布和进程状态。
- 将 `SessionStatus` 限定为 Python 子进程生命周期状态，避免与 Telegram 授权状态混用。
- 将 `start(config)` 的配置边界限定为 Python runtime 运行所需配置，至少包括代理配置，并允许未来扩展运行环境、目录、超时等配置。
- 定义统一 JSON Lines envelope 协议，所有 Java 输入和 Python 输出都使用 `id`、`type`、`content`、可选 `replyInputId`。
- 支持 `input`、`reply`、`log`、`message` 类型，并允许未来扩展其他类型。
- 支持两类消息输出：无 `replyInputId` 的实时消息，以及带 `replyInputId` 的拉取结果消息。
- 保持敏感信息保护要求，避免在日志、错误和持久化记录中泄漏 API hash、代理密码、验证码、二步验证密码或消息正文。

**Non-Goals:**

- 不在本阶段重新设计 control-server REST API、数据库结构、前端页面或推送规则。
- 不要求在本阶段完成所有外部调用方迁移策略；重点是模块内部 Java/Python 协议和分层。
- 不将 `TelegramSession` 设计成 Telegram 业务对象；它不理解 `submit_code`、`chatId`、`AuthorizationState` 或 `TelegramMessageEvent`。
- 不引入新的消息持久化能力；消息正文仍只允许按既有隐私边界在内存和显式通知模板中使用。

## Decisions

### Decision 1: 将进程通信层抽象为 `TelegramSession`

`TelegramSession` 提供：

- `start(config)`：启动 Python 进程并应用 runtime 配置。
- `send(String str)`：向 Python stdin 写入一个 JSON 字符串。
- `getPublisher()`：发布 Python 子进程产生的 JSON 字符串输出。
- `stop()`：停止 Python 进程。
- `getStatus()`：返回子进程生命周期状态。

该接口不解析 Telegram 业务内容，不维护账号授权状态，也不分发 `TelegramMessageEvent`。这样可以把低层进程可靠性、协议读写和高层业务适配分离。

**Alternative considered:** 继续在 `PythonSubprocessTelegramAccountSessionManager` 内部重构私有方法。该方式改动较小，但仍会让业务接口和进程协议耦合，无法形成可复用、可测试的基础通信层。

### Decision 2: `SessionStatus` 表达进程生命周期，而非 Telegram 授权状态

`SessionStatus` 应表达 Python 子进程状态，例如 `NEW`、`STARTING`、`RUNNING`、`STOPPING`、`STOPPED`、`FAILED`。`WAIT_CODE`、`READY`、`LOGGED_OUT` 等仍属于 Telegram 业务授权状态，由上层协议 client 或 account session manager 解释。

**Alternative considered:** 复用现有 `AuthorizationState`。该方式会把 Python 进程是否存活和 Telegram 账号是否授权混为一谈，不利于定位进程崩溃、协议错误和业务授权失败。

### Decision 3: `start(config)` 只承载 runtime 运行配置

`TelegramSessionConfig` 面向“Python 子进程如何正常运行”，至少支持代理配置，并可扩展：

- Python executable、worker script、working directory、extra args。
- runtime data directory 或 session directory。
- 代理链配置。
- 环境变量和启动/停止超时。
- 必要的依赖检查配置。

`submitCode`、`chatId`、拉取未读消息参数等业务字段不得放入 `start(config)`，必须通过 `send(String str)` 发送 `type=input` envelope 表达。

**Alternative considered:** 在 `start(config)` 中继续下发账号登录参数、手机号和业务命令。该方式能兼容旧协议，但会使 `TelegramSession` 变成业务 session，不符合分层目标。

### Decision 4: 使用统一 JSON Lines envelope 协议

每个 stdin/stdout 单元都是一行 UTF-8 JSON object，顶层字段为：

- `id`：当前 envelope 的唯一标识。
- `type`：协议类型。
- `content`：业务或日志内容，可为 object 或 string。
- `replyInputId`：可选，表示该输出由哪一次输入直接触发。

Java -> Python 当前仅定义：

- `input`

Python -> Java 当前定义：

- `reply`
- `log`
- `message`

协议解析层应允许未知 `type` 作为未来扩展，但当前业务适配层只处理已支持类型。

**Alternative considered:** 在旧扁平协议中补充 request id 字段。该方式能解决部分关联问题，但顶层业务字段仍会和协议字段混杂，日志、消息、错误仍缺少统一形态。

### Decision 5: `replyInputId` 是输入输出关联的唯一标准

Python 对某个 input 的完成响应必须输出 `type=reply` 并包含 `replyInputId=<input.id>`。如果某个 input 会产生多条消息，Python 应先输出零条或多条 `type=message` 且包含同一个 `replyInputId`，最后输出一条 `type=reply` 表示该 input 处理完成。

实时 Telegram 新消息没有 `replyInputId`。这使业务层可以根据 `replyInputId` 区分：

- 无 `replyInputId`：异步实时消息。
- 有 `replyInputId`：某次主动输入导致的输出，例如拉取未读消息结果。

**Alternative considered:** 将拉取未读消息全部放在单个 `reply.content.messages` 数组中。该方式简单，但会让实时消息和拉取消息走两套处理路径，并可能产生过大的单行 JSON。

### Decision 6: `message` 作为一等输出类型

`message` 不再只是旧协议中的业务事件，而是 envelope 的一等 `type`。它承载 Telegram 消息数据，包括实时新消息和拉取未读消息结果。最终是否转换为 `TelegramMessageEvent` 由上层业务适配完成。

**Alternative considered:** 使用通用 `event` 类型，并在 `content.name` 中放 `telegram.message`。该方式更通用，但当前明确需要消息作为核心数据流，直接使用 `message` 更清晰；未来仍可新增 `event` 类型承载非消息异步事件。

### Decision 7: publisher 发布原始 JSON 字符串

`getPublisher()` 发布 Python 输出的 JSON 字符串，而不是 Java 业务对象。`TelegramSession` 可以做基础 JSON 合法性检查和 stderr 包装，但不应将输出转换为授权状态或消息事件。

stderr 仍需被读取以避免进程阻塞。若 stderr 产生内容，Java runtime 可以将其包装为 `type=log` envelope 后发布，确保 publisher 对外仍只发布 JSON 字符串。

**Alternative considered:** publisher 直接发布强类型 Java 对象。该方式便利上层业务，但会让底层接口依赖协议模型和业务模型，削弱 `TelegramSession` 的基础通信定位。

### Decision 8: 保留业务适配层，但让其依赖 `TelegramSession`

现有 `TelegramAccountSessionManager` 仍是 starter 对 control-server 暴露的业务边界。后续实现中，它可以持有或创建 `TelegramSession`，通过 `send` 发送业务 input，并订阅 publisher 解释 `reply`、`log` 和 `message`。

这样既不破坏项目现有模块边界，也能逐步将业务适配从子进程管理细节中拆出。

## Risks / Trade-offs

- **[Risk] 协议变更会打破现有 fake worker 和测试。** → 迁移测试到 envelope 协议，并新增底层 `TelegramSession` 交互测试覆盖 input/reply/log/message。
- **[Risk] `Flow.Publisher` 是 hot stream，订阅时机可能导致早期输出丢失。** → 明确第一版 publisher 不保证历史 replay；如测试或诊断需要，可在实现中加入有界缓冲，但不作为业务语义依赖。
- **[Risk] stderr 包装为 log 后可能泄漏敏感信息。** → 包装前必须走统一脱敏逻辑，且 Python 侧仍应避免向 stderr 输出敏感字段。
- **[Risk] 拉取未读消息通过多条 `message` 输出增加上层聚合复杂度。** → 使用最终 `reply` 作为完成信号，上层按 `replyInputId` 聚合即可；换来更统一的数据流和更低单条 JSON 体积。
- **[Risk] 当前只定义 `message`，未来非消息异步事件可能继续增加顶层 type。** → 协议明确允许扩展类型；如异步事件种类增多，可新增 `event` 而不破坏现有 `message` 语义。
- **[Risk] `start(config)` 中代理配置属于 runtime 还是业务配置的边界可能模糊。** → 本设计将代理视为 Python runtime 正常连接 Telegram API 所需的运行配置，而非某次业务命令参数。

## Migration Plan

1. 新增 `TelegramSession`、`SessionStatus`、`TelegramSessionConfig` 等底层模型和默认 subprocess 实现。
2. 调整 Python `protocol.py`，提供 envelope 读取和 `reply`、`log`、`message` 输出 helper。
3. 调整 Python worker 命令入口，使其从 `type=input` 的 `content` 中读取业务命令，并通过 envelope 输出结果。
4. 调整 Java 业务适配层，使其通过 `TelegramSession` 发送 input，并订阅 publisher 处理 `reply`、`log` 和 `message`。
5. 迁移 fake worker 测试、Java runtime 测试和 Python helper 测试到新协议。
6. 更新模块 README，删除旧扁平协议示例，补充 envelope 协议示例。

如迁移过程中出现兼容风险，可以先在模块内部保留旧业务适配入口，但 Python worker stdout/stdin 协议应以新 envelope 为目标形态。

## Open Questions

- `TelegramSession` 是否允许同一个实例 `stop()` 后再次 `start(config)`，还是定义为单次生命周期对象？建议第一版采用单次生命周期，重启时创建新实例。
- publisher 是否需要有界 replay 缓冲？建议第一版不保证历史 replay，只保证订阅后的输出。
- 是否在本变更中实现真实的 `messages.fetchUnread` 业务命令，还是仅在协议层定义 `message(replyInputId=...) + reply` 的输出语义？建议 spec 中先定义协议语义，具体业务命令实现可按任务拆分。 
