## Why

`telegram-python-subprocess-runtime` 目前由 `PythonSubprocessTelegramAccountSessionManager` 同时承担三件事:对 control-server 暴露 starter 业务契约、直接持有并操作 `TelegramSession` 子进程、以及把协议 envelope 翻译成 Telegram 业务语义(授权状态、登录流程、消息事件)。这导致上层(control-server)被迫通过“命令式 + push 监听器”的散装 API 操作 Telegram,既无法获得一个完整的“单账号 Telegram 业务对象”,也无法把“何时拉取未读、何时推送通知”这类策略与“如何与 Python 通信”这类能力解耦。

上一个变更(`refactor-python-session-protocol`)已经把最底层的子进程交互对象 `TelegramSession` 抽离出来。本变更在其之上引入 `TelegramClient`——一个把 `TelegramSession` 包装成完整 Telegram 业务能力的单账号对象,只暴露能力(`getState` / `login` / `peekUnreadMessage` / `updateProxies` / `close`),不包含任何调度策略。

## What Changes

分两步交付。Step 1 是纯新增、零修改现有代码,可在不影响现有 push 通知链路的前提下独立完成并验证;Step 2 在 Step 1 验证通过后接 control-server。

### Step 1:纯新增,零修改

- 新增 `TelegramClient` 接口及其默认实现,包装 `TelegramSession`,对外暴露:`getState()`、`login()` → `LoginFlow`、`peekUnreadMessage(chatId)`、`updateProxies(...)`、`close()`。
- 新增 `LoginFlow`,与 `TelegramClient` 共享同一份业务状态,提供 `getState()` 和按当前授权环节多态分发的 `submit(value)`。
- 新增 `TelegramMessage`(放 `telegram-spring-boot-starter`),作为 `peekUnreadMessage` 的返回元素,`receivedAt` 为系统时区 `LocalDateTime`。
- `TelegramClient` 内部订阅 `TelegramSession.getPublisher()`,按命令 disposition 路由 envelope:`reply` → 命令等待;`message` 带 `replyInputId` 且属 peek → 收集进返回列表;其余 `message` 与 `log` → 仅记元数据日志。
- `TelegramClient` 内部实现懒启动 `ensureRunning()`:首次操作时按构造时传入的 `TelegramSessionConfig` 启动 `TelegramSession`,已运行则 no-op,`FAILED` 后下次操作自动重启,且并发首次调用只启动一次。
- Python worker 把当前 stub 的 `fetch_unread` 命令实现为真实拉取:按 limit 调 `get_chat_history`,输出带 `replyInputId` 的 `message` envelope,最后输出匹配的 `reply`。
- Step 1 不修改 `PythonSubprocessTelegramAccountSessionManager`、不修改 starter 契约、不修改 control-server;现有 push 通知链路继续工作。`TelegramClient` 通过自有单元测试(stub session)和手动真机验证稳定性。

### Step 2:接 control-server(依赖 Step 1 验证通过)

- starter 契约 `TelegramAccountSessionManager` 调整:新增 pull 方法 `List<TelegramMessage> peekUnreadMessages(accountId, chatId)`;移除消息 push 相关方法(`subscribe` / `publishTestMessage` / 无返回值的 `scan`)。
- `PythonSubprocessTelegramAccountSessionManager` 变薄为注册表:`Map<accountId, TelegramClient>`,各方法委托给对应 client;**不再直接持有 `TelegramSession`、不再订阅 publisher**,彻底断开直接操作 session 的逻辑。
- control-server 通知链路改为 pull 驱动:`TelegramUnreadScanScheduler` 周期性调 `peekUnreadMessages` 拿到消息列表,逐条喂给 `NotificationRuleService`;`NotificationRuleService` 不再 subscribe,改为被 scheduler 调用。
- `TelegramMessage` 取代 `TelegramMessageEvent`;`NotificationRuleService.handle` 签名与模板渲染(`receivedAt` 直接是 `LocalDateTime`)随之调整。
- status 变更仍以“命令回复回调”形式从 client 回传 manager 扇出(供 `TelegramAccountService` 落库 `authorizationState`),不改为轮询。

## Capabilities

### New Capabilities

- `telegram-client-business-layer`:把 `TelegramSession` 包装成单账号 Telegram 业务能力对象 `TelegramClient`,定义 `getState`、`login` → `LoginFlow`、`peekUnreadMessage`、懒启动 `ensureRunning`、按 disposition 的 envelope 路由、`PendingFetch` 收集、`TelegramMessage` 返回类型与隐私边界。

### Modified Capabilities

- `python-telegram-worker-runtime`:worker 把 stub 的 `fetch_unread` 实现为真实未读消息拉取;Step 2 起 `PythonSubprocessTelegramAccountSessionManager` 不再直接操作 `TelegramSession`,改为委托 `TelegramClient`,且 starter 契约由消息 push 改为消息 pull。

## Impact

- **telegram-python-subprocess-runtime**:新增 `TelegramClient`、`LoginFlow`、`PendingFetch`;Step 2 重写 `PythonSubprocessTelegramAccountSessionManager` 内部实现;`worker.py` 实现 `fetch_unread`。
- **telegram-spring-boot-starter**:新增 `TelegramMessage` 类型;Step 2 调整 `TelegramAccountSessionManager` 契约(+peek / -消息push)并相应调整 `InMemoryTelegramAccountSessionManager` 测试桩。
- **telegram-notifier-control-server**:Step 2 重写 `TelegramUnreadScanScheduler` 为 pull、`NotificationRuleService` 改为被 scheduler 调用、删除 push 接线、`TelegramMessage` 取代 `TelegramMessageEvent`。
- **行为取舍**:Step 2 后通知由实时 push 改为轮询驱动,最坏延迟 = scheduler 轮询间隔(默认 10s);实时 `_on_message` 推送在 client 内仅记日志、不接通知。
- **隐私边界不变**:消息正文仍只在 `type=message` envelope 与显式内存业务处理中出现;client 日志只含元数据,不含 text。
- 数据库结构、推送渠道、前端不受影响。
