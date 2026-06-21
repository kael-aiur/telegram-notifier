## Context

上一个变更 `refactor-python-session-protocol` 把最底层的 Python 子进程交互对象 `TelegramSession` 抽离:它只负责进程生命周期、stdin 写入、publisher 发布和子进程状态,刻意不懂任何 Telegram 业务(`submitCode`、`chatId`、`AuthorizationState`)。

但 `TelegramSession` 之上目前没有“单账号业务对象”这一层。所有业务语义(授权状态、登录流程、消息事件、未读拉取)仍直接由 `PythonSubprocessTelegramAccountSessionManager` 处理——它一边实现 starter 对 control-server 的业务契约,一边直接持有 `TelegramSession`、订阅 publisher、维护 `WorkerState`、做命令响应等待。这造成两个问题:

1. 上层没有完整的“一个账号的 Telegram 业务对象”,只能通过散装的 `start/submitPhone/submitCode/scan/subscribe` 命令式 API 拼装出业务行为。
2. “如何与 Python 通信”与“何时拉取未读、何时推送通知”耦合在一起:通知目前是 push 驱动(`NotificationRuleService` 订阅 manager 的消息监听器),无法把轮询策略单独放在更上层。

本设计在 `TelegramSession` 之上引入 `TelegramClient`:把 session 包装成完整的单账号 Telegram 业务能力层,只暴露能力,不含策略。配合 control-server 侧通知改 pull,实现“能力 vs 策略”的干净分离。

## Goals / Non-Goals

**Goals:**

- 引入 `TelegramClient` 作为单账号 Telegram 业务能力对象,包装 `TelegramSession`,暴露 `getState`、`login` → `LoginFlow`、`peekUnreadMessage`、`updateProxies`、`close`。
- `TelegramClient` 内部拥有 publisher 订阅与 envelope 路由,上层不再直接接触 `TelegramSession` 或协议细节。
- `TelegramClient` 内部拥有懒启动 `ensureRunning()`,上层无需显式启动子进程。
- 引入 `LoginFlow`,与 client 共享状态,提供按当前授权环节多态分发的 `submit`。
- 引入 `TelegramMessage`(starter),`receivedAt` 为系统时区 `LocalDateTime`,作为 peek 返回元素。
- worker 把 `fetch_unread` 实现为真实未读消息拉取。
- 通知链路改为 pull 驱动(Step 2),scheduler 用 client 的 peek 能力实现轮询策略。
- 保持隐私边界:消息正文不进入 client 日志。

**Non-Goals:**

- 不改变数据库结构、推送渠道、前端。
- 不重新设计 `TelegramSession` 本身(它是 dumb 传输层,保持不变)。
- 不在 `TelegramClient` 层引入任何调度/轮询策略(那是更上层的事)。
- 不要求 Step 1 触碰 control-server;Step 1 是纯新增、零修改。

## Decisions

### Decision 1:`TelegramClient` 是 runtime 内部的单账号业务能力层

`TelegramClient` 放在 `site.kael.telegram.python`(runtime 模块内部),包装 `TelegramSession`。`PythonSubprocessTelegramAccountSessionManager` 变薄为注册表:`Map<accountId, TelegramClient>`,各 starter 接口方法委托给对应 client。starter 对 control-server 的契约(`TelegramAccountSessionManager`)接口形状在 Step 2 调整,但仍是 control-server 唯一依赖的边界;control-server 不直接持有 `TelegramClient`。

这样 `TelegramClient` 成为 `TelegramSession` 之上唯一懂 Telegram 业务的层,manager 退化为“多账号注册 + 跨账号扇出”。

**Alternative considered:** 把 `TelegramClient` 提升到 starter 作为新的通用单账号接口,让 control-server 直接持有。该方式更彻底,但是跨模块契约改动,牵动 control-server 所有 service 的重接线,且让 starter 依赖一个偏实现的业务对象。本设计优先在 runtime 内部建立业务层,starter 契约只做最小调整。

### Decision 2:懒启动 `ensureRunning()` 由 `TelegramClient` 持有

`TelegramClient` 构造时接收一个构造好的 `TelegramSessionConfig`(以及手机号),内部持有 `ensureRunning()`:首次操作时调 `session.start(config)`,已运行则 no-op,`FAILED` 后下次操作自动重启。`ensureRunning()` 是 synchronized 且幂等的,保证并发首次调用只启动一次。manager 的显式 `start()`(供 `autoStartReadyAccounts` 用)走 `client.start()` 立即起;`peek`/`getState`/`login` 等走 `ensureRunning()` 懒起,两条路汇到同一个“start once”守卫。

`TelegramSessionConfig` 的组装(apiId/apiHash/dataDir/workerScript/proxies)留在 manager——它才知道这些值。`TelegramClient` 只接收并使用 config,专注“生命周期 + 协议翻译”。这也让 client 的单测更好写:注入 config + stub session。

**Alternative considered:** (a) 在 `TelegramClient` 与 `TelegramSession` 之间再塞一层 `ManagedTelegramSession` 专门管生命周期。更解耦但多一层,当前业务量不值得。(b) 把配置和懒启动下沉进 `TelegramSession`。这违背 `TelegramSession` “intentionally not a business object” 的既定边界,会让 session 懂 apiId/proxy,不予采用。

### Decision 3:通知改为 pull 驱动,`TelegramClient` 不做消息 push 扇出

`TelegramClient` 订阅 publisher 后,对 envelope 的处置:`reply` → 命令等待;`message` 带 `replyInputId` 且属 peek → 收集;实时 `message`(无 `replyInputId`)与 `log` → 仅记元数据日志,不向外推送。通知由 control-server 的 scheduler 周期性调 `peekUnreadMessages` 拿到消息列表再喂给 `NotificationRuleService` 实现。

这把“能力”(client 能 peek 未读)与“策略”(scheduler 多久查一次)彻底分开,client 不需要知道调度。

status 变更仍以“命令回复回调”形式从 client 回传 manager 扇出(供 `TelegramAccountService` 落库 `authorizationState`),因为 status 不是消息文本、无隐私问题,且本质是 `start`/`submit` 等 reply 的副产物,不是独立流式 push。

**Alternative considered:** 保留 push,manager 在每个 client 上设置 message-listener 钩子扇出给 `NotificationRuleService`(原 Option 1)。该方式改动最小且保留实时性,但保留了 client 与通知策略的耦合,且 manager 仍需持有扇出逻辑。本设计按用户取向选择 pull,换来能力/策略分离;代价是通知延迟(见 Risks)。

### Decision 4:`LoginFlow` 与 `TelegramClient` 共享状态,`submit` 按当前环节多态分发

`LoginFlow` 不另存状态,其 `getState()` 即 `client.getState()`。`submit(value)` 根据当前 `AuthorizationState` 自动选择发送的命令:

| 当前状态 | `submit(value)` 实际发送 |
|---|---|
| `WAIT_PHONE` | `submit_phone(value)` |
| `WAIT_CODE` | `submit_code(value)` |
| `WAIT_PASSWORD` | `submit_password(value)` |
| `READY` | no-op / 抛异常(已登录) |

`submit` 返回提交后的新 `AuthorizationState`(复用现有同步等待机制,见 Decision 6)。上层驱动逻辑极简:`while (flow.getState() != READY) flow.submit(从用户拿到的值)`。

构造时的手机号进 `start` 命令(worker 据此开始);`submit` 只在落到 `WAIT_PHONE` 时才提交手机号,二者不冲突。

**Alternative considered:** 在 `LoginFlow` 上提供显式的 `submitPhone` / `submitCode` / `submitPassword` 三个方法。该方式更直白,但把“当前该提交什么”的判断推给上层,容易在错误环节提交错误值。多态分发由 client 根据权威状态决定,更不易错。

### Decision 5:按“命令 disposition”路由 message envelope

同一个 `message + replyInputId`,在 `peekUnreadMessage` 命令下要**收集进返回列表**,在其他命令(如未来的 scan 复用)下可能要**扇出/丢弃**。因此 client 发命令时登记该 inputId 的 disposition(collect / fanout / status-only),收到带 `replyInputId` 的 `message` 时按 disposition 路由;实时消息(无 `replyInputId`)走“仅记日志”。

**Alternative considered:** 纯按 `replyInputId` 是否存在路由(有就收集,无就日志)。但 scan 语义下带 `replyInputId` 的 message 也要进通知流而非被某个调用收集,所以判据必须是命令 disposition,而非 envelope 字段。

### Decision 6:用 `PendingFetch` 收集 peek 结果

现有 manager 的 `PendingReply`(单 reply + `CountDownLatch`)只收一个 status。`peekUnreadMessage` 需要收**一组** `message` envelope(按 `replyInputId` 匹配)直到匹配的 `reply` 到达。新增 `PendingFetch`:收集 `List<TelegramMessage>` + 终止信号(匹配 reply 到达或超时)。它与 `PendingReply` 并列,各自服务一种命令语义。`submit`/`start`/`stop` 等 status-only 命令仍用 `PendingReply`。

**Alternative considered:** 把所有未读消息塞进单个 `reply.content.messages` 数组。该方式简单,但与现有“多条 message + 终止 reply”协议语义不一致,且可能产生过大的单行 JSON。

### Decision 7:`TelegramMessage` 放 starter,`receivedAt` 为系统时区 `LocalDateTime`;Step 2 取代 `TelegramMessageEvent`

`TelegramMessage` 定义在 `telegram-spring-boot-starter`,字段对齐现有 `TelegramMessageEvent`(`accountId`、`chatId`、`messageId`、`chatTitle`、`chatType`、`senderId`、`senderName`、`senderUsername`、`receivedAt`、`text`),唯一区别是 `receivedAt` 由 `Instant` 改为系统时区 `LocalDateTime`。Step 1 即定义在 starter(新增文件,不算“动 control-server”,也免去 Step 2 再搬);Step 2 中 `TelegramMessage` 取代 `TelegramMessageEvent`,因为 pull 模式下 push event 角色消失。

**Alternative considered:** (a) 复用 `TelegramMessageEvent`(`Instant`),由调用方在渲染边界转 `LocalDateTime`。零新增类型,但违背“返回类型即本地时间”的明确要求。(b) `TelegramMessage` 放 runtime 内部。但 Step 2 control-server 要通过 starter 契约拿到它,放 runtime 会迫使 Step 2 再搬一次模块。

### Decision 8:两步交付,Step 1 纯新增并存隔离

Step 1 只新增文件(`TelegramClient`、`LoginFlow`、`PendingFetch`、`TelegramMessage`、worker `fetch_unread` 实现、client 测试),不修改任何现有代码。现有 `PythonSubprocessTelegramAccountSessionManager` 原样运行,push 通知照常工作,作为安全网。`TelegramClient` 与现有 manager **并存隔离**——client 通过自有单测(stub session)和手动真机验证稳定性。

代价是 Step 1 期间存在临时代码重复(协议路由、`PendingReply`、envelope 构造等 manager 私有逻辑,client 会重写一份),在 Step 2 一次性消除。

**Alternative considered:** Step 1 就让 manager 内部委托 client(契约不变)。无重复,但 client 立刻承载真实流量,等于变相“已接上”,违背“先测稳”;若 client 有 bug 会直接拖垮在跑的通知。本设计选择并存隔离,用临时重复换 Step 1 的零风险。

## Risks / Trade-offs

- **[Risk] Step 1 期间代码重复。** → 并存隔离的固有代价。重复逻辑(路由、`PendingReply`、envelope 构造)集中在 manager 与 client 两处,Step 2 重写 manager 时一次性消除。若重复不可接受,可折中抽取共享 helper 让两边复用,但会引入 Step 1 的重构,默认不做。
- **[Risk] Step 2 后通知延迟由实时降为轮询级(默认 10s)。** → 用户已知并接受(换取能力/策略分离)。scheduler 轮询间隔可配;对自托管个人通知器 10s 级延迟可接受。
- **[Risk] `LocalDateTime` 丢失时区语义。** → 同一墙钟时间在夏令时切换点可能对应两个 instant。对单用户自托管服务基本无害;这是有意识的信息降级,在 spec 中记录。
- **[Risk] `ensureRunning()` 并发首次启动。** → 必须 synchronized 且幂等,保证“start exactly once”。单测覆盖并发首次调用只启动一次。
- **[Risk] `fetch_unread` 真实现需要 Python 端工作,且要决定拉取上限。** → 当前是 stub(返回 `count:0`),且无人调用,实现是安全的新增。limit 策略默认 `min(unreadCount, maxPerChat)`,避免单 chat 拉取过多。
- **[Risk] worker 实时 `_on_message` 仍发送但被 client 丢弃。** → Step 2 前白跑且多一份带 text 的 envelope 在管道里(但 client 只记元数据日志,不泄漏)。默认先保留(改动最小),Step 2 可选关闭 worker 实时 handler 以省流量。
- **[Risk] ERROR 状态的自动重启可能误重启 auth 级失败。** → 进程级失败(子进程崩溃)可由 `ensureRunning` 自动重启;auth 级失败(验证码错误)不能重启自愈,只能人工重新 `login`。两者都映射 `AuthorizationState.ERROR`,靠 `errorMessage` 区分;`ensureRunning` 只对进程级(子进程未存活)重启。

## Migration Plan

**Step 1(纯新增,可独立完成并验证):**

1. 在 starter 新增 `TelegramMessage`(`receivedAt` 为系统时区 `LocalDateTime`)。
2. 在 runtime 新增 `TelegramClient` 接口及默认实现:构造接收手机号 + `TelegramSessionConfig` + `TelegramSession`(或工厂);内部订阅 publisher、按 disposition 路由、实现 `ensureRunning`。
3. 新增 `LoginFlow`:共享 client 状态,`submit` 按 `WAIT_*` 多态分发,返回新 `AuthorizationState`。
4. 新增 `PendingFetch` 收集器。
5. worker.py 把 `fetch_unread` 从 stub 实现为真实拉取(`get_chat_history` + limit,多条 message + 终止 reply)。
6. 新增 client 单元测试(stub `TelegramSession`,覆盖 getState/login/ensureRunning/peek 收集/路由/隐私/并发)。
7. 手动真机验证(沿用 README manual validation:建号 → login → peek 某 chat 未读)。
8. 运行 `mvn test`、Python helper 测试、`openspec validate`,确认 Step 1 稳定。

**Step 2(接 control-server,显式依赖 Step 1 验证通过):**

1. starter 契约 `TelegramAccountSessionManager`:`+peekUnreadMessages(accountId, chatId)`、`-subscribe`/`-publishTestMessage`/`-void scan`;`TelegramMessage` 取代 `TelegramMessageEvent`。
2. 调整 `InMemoryTelegramAccountSessionManager` 测试桩与新契约一致。
3. runtime `PythonSubprocessTelegramAccountSessionManager` 重写为注册表:`Map<accountId, TelegramClient>`,委托各方法;移除直接持 session、订阅 publisher 的逻辑。
4. control-server `TelegramUnreadScanScheduler` 改 pull:周期调 `peekUnreadMessages`,逐条喂 `NotificationRuleService`。
5. control-server `NotificationRuleService`:去掉 `subscribe`,改为被 scheduler 调用;`handle` 签名改 `TelegramMessage`;模板渲染用 `LocalDateTime`。
6. control-server 删除 push 接线与 `TelegramMessageEvent` 残留引用。
7. 运行 `mvn test`、`openspec validate`,确认端到端通知(pull)正常。

如 Step 2 迁移中出现兼容风险,可先保留 manager 旧路径作为回退,但目标形态是 manager 完全委托 client、彻底断开直接操作 session。

## Open Questions

- `peekUnreadMessage` 是否需要支持批量 chatId(减少 scheduler 多次 subprocess 往返)?当前倾向单 chatId、scheduler 循环,chat 个数少时足够;若实测往返开销显著再扩展批量。
- `chatId` 类型:`long` 还是 `String`?现有协议与 `TelegramMessageEvent` 均为 `long`,Pyrogram id 为 int,倾向保持 `long` 一致。
- `fetch_unread` limit 策略:默认 `min(unreadCount, maxPerChat)`,`maxPerChat` 是否需要可配?
- worker 实时 `_on_message` 是否在 Step 2 关闭(彻底转为轮询),还是继续发送由 client 记日志丢弃?倾向先保留,Step 2 后按需关闭。
- `ERROR` 自动重启是否需要退避(避免子进程反复崩溃时无限重启)?倾向第一版加简单次数上限。
