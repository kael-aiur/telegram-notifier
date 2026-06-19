## Context

项目当前由 `telegram-notifier-control-server` 负责账号、代理、规则、推送和统计，`telegram-spring-boot-starter` 暴露 `TelegramAccountSessionManager` 作为 Telegram 集成边界。现有 starter 实现是 `InMemoryTelegramAccountSessionManager`，只能模拟授权状态和测试消息，无法真实连接 Telegram。

真实对接需要跨越 starter、control server 和前端体验，并引入 Telegram 客户端运行时。设计目标是让真实客户端细节留在独立运行时实现模块内部，control server 继续只依赖稳定接口和领域对象。

## Goals / Non-Goals

**Goals:**

- 使用真实 Telegram client runtime 完成个人账号登录、会话持久化和消息监听。
- 保持 `TelegramAccountSessionManager` 作为 control server 与 Telegram 运行时之间的主要边界。
- 支持 `api_id`、`api_hash`、客户端数据目录和可插拔运行时切换配置。
- 将 Telegram 授权状态映射到现有 `AuthorizationState`，让当前 `start`、`phone`、`code`、`password` API 可继续使用。
- 将账号代理链应用到 Telegram 客户端，并把活动代理和错误信息回写给账号状态。
- 确保消息正文不进入数据库、统计表或持久日志。

**Non-Goals:**

- 不实现多用户管理或多管理员权限模型。
- 不存储 Telegram 历史消息或完整消息审计记录。
- 不在本变更中支持所有 Telegram 消息类型的完整富媒体解析。
- 不改变现有规则、推送渠道和统计数据模型，除非真实客户端接入所必需。

## Decisions

### 1. 通过运行时 SPI 隔离真实 Telegram 实现

保留 `TelegramAccountSessionManager` 作为 Java 与 Telegram 运行时交互的稳定接口。`telegram-spring-boot-starter` 只提供公共 API、配置对象和默认内存实现；真实运行时由独立模块提供同一接口的 bean。

control server 仍通过接口调用 `start`、`stop`、`submitPhone`、`submitCode`、`submitPassword`、`status`、`updateProxies` 和 `subscribe`，不直接依赖 Pyrogram、TDLight 或任何具体客户端。

备选方案是在 control server 直接集成 Telegram client，但这会把 Python worker、TDLight 或其他客户端细节扩散到业务服务里，破坏当前模块边界。

### 2. 使用配置选择运行时实现

新增 starter 配置属性，例如：

- `telegram.client.mode=MEMORY|PYTHON_SUBPROCESS|TDLIGHT`
- `telegram.client.api-id`
- `telegram.client.api-hash`
- `telegram.client.data-dir`

默认仍使用 `memory`，避免测试和本地未配置环境启动失败。显式设置真实运行时时，如果 `api-id`、`api-hash`、数据目录或运行时特定配置不可用，账号启动必须失败并返回连接错误。

### 3. 首个真实运行时采用 Python 子进程

新增 `telegram-python-subprocess-runtime` 模块，实现 `TelegramAccountSessionManager`。该模块通过 `ProcessBuilder` 管理 CPython worker 子进程，并用 stdin/stdout JSON Lines 协议发送命令和接收事件。

Python worker 负责：

- 使用 Pyrogram 或 Telethon 连接 Telegram。
- 处理手机号、验证码、二步验证密码和 session 文件。
- 按启动配置应用代理。
- 将状态变化和新消息作为 JSON Lines 事件写回 Java。

Java 子进程运行时负责：

- 按账号启动、停止和重启 worker。
- 配置变更或代理变更时重启对应账号 worker。
- 将现有 Java API 调用转换为 JSON 命令。
- 将 worker 事件转换为 `TelegramConnectionStatus` 和 `TelegramMessageEvent`。

### 4. TDLight 作为后续可选运行时

TDLight 仍可通过单独模块实现同一接口，例如 `telegram-tdlight-runtime`。`telegram-tdlight-classifier-all` 负责聚合 TDLight Java 和主流 native classifier，后续也可以替换为平台专用 classifier 模块。control server 不感知当前选择的是 Python 子进程还是 TDLight。

### 5. 每账号独立会话目录

账号会话文件放在 `telegram.client.data-dir/accounts/{accountId}` 之下。这样重启后 Telegram 授权可复用，也避免多个账号之间共享授权数据库。

删除账号时是否删除会话目录不在本变更自动执行，避免误删可恢复登录状态；后续可以增加显式“清除会话”操作。

### 6. 状态同步采用回调内存态加控制服务持久化

真实运行时维护每个账号的内存状态，包括授权状态、活动代理、错误信息和 client 引用。control server 现有 `TelegramAccountService` 在显式 API 调用后调用 `saveStatus` 持久化状态。

为了处理异步状态变化，starter 需要新增或复用状态变更订阅机制。最小方案是在 control server 定期调用 `status` 或在关键操作后刷新；完整方案是新增 `TelegramConnectionStatusListener`，由 control server 订阅并持久化异步授权状态和错误。实现阶段优先采用 listener，减少 UI 状态滞后。

### 7. 代理链映射到 Telegram 客户端连接设置

启动账号时，真实运行时读取 `TelegramAccountConfig.proxies()` 中启用代理，按优先级选择第一个代理作为活动代理。`updateProxies` 被调用时，运行时更新该账号的代理设置；Python 子进程运行时通过停止并重启对应账号 worker 应用新代理，保留账号会话目录和授权状态。

HTTP、HTTPS、SOCKS5 映射必须只在 starter 内部完成。代理密码不得写入日志。

### 8. 消息事件只做轻量归一化

真实运行时收到新消息后提取：

- `accountId`
- Telegram chat ID
- source type / source label
- sender ID / sender label
- receivedAt
- text

文本消息直接填充 `text`。无法提取文本的消息可以发布空文本事件或跳过；不得让解析失败影响会话。

## Risks / Trade-offs

- [Risk] Python worker 增加多语言部署复杂度。→ Mitigation：Java 通过 `ProcessBuilder` 管理生命周期，运行时配置明确 `python` 可执行文件、worker 脚本和数据目录；Docker 镜像可同时包含 Java 与 Python 依赖。
- [Risk] TDLight native 依赖复杂。→ Mitigation：TDLight 作为可选实现保留在独立模块，默认先推进 Python 子进程运行时。
- [Risk] 授权状态异步更新，前端可能看不到最新状态。→ Mitigation：新增状态 listener 并在 control server 持久化最新状态；前端保留 Refresh。
- [Risk] 代理更新时底层客户端不支持无损热切换。→ Mitigation：允许运行时重连，并通过 `connection_error` 暴露失败原因。
- [Risk] 消息正文泄漏到日志或持久层。→ Mitigation：消息事件处理和错误日志禁止拼接正文；测试覆盖统计和投递记录不含正文。
- [Risk] 多账号并发 client 消耗资源。→ Mitigation：按账号懒启动 client，停止账号时释放连接和线程资源。

## Migration Plan

1. 新增配置属性和自动配置条件，默认 `memory`，不影响现有部署。
2. 新增真实运行时实现和每账号 session 管理，不改 control server API。
3. control server 增加状态变更订阅持久化，保留现有显式状态保存。
4. 前端补充真实运行时配置缺失和授权状态提示。
5. 部署时管理员配置 `telegram.client.mode=PYTHON_SUBPROCESS`、`api-id`、`api-hash`、数据目录和 Python worker 脚本后重启服务。

回滚方式：将 `telegram.client.mode` 改回 `memory` 或移除真实运行时配置，重启服务即可回到当前模拟行为；数据库 schema 不需要回滚。

## Open Questions

- Python worker 的具体协议字段和 worker 脚本实现需要在实现阶段固化，并补充协议兼容性测试。
- TDLight 可选运行时是否继续保留，需要在 Python 子进程方案稳定后再决定。
- 是否需要在 UI 增加“清除 Telegram 会话”操作，避免手机号切换或授权损坏时只能手工删除文件。
- 收到非文本消息时应发布空文本事件还是完全跳过，需要结合规则匹配体验确认。
