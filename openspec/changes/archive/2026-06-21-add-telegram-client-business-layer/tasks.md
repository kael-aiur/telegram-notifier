## 1. Step 1 — `TelegramClient` 业务层(纯新增,零修改现有代码)

> 本阶段不修改 `PythonSubprocessTelegramAccountSessionManager`、starter 契约、control-server。现有 push 通知链路继续运行。

- [x] 1.1 在 `telegram-spring-boot-starter` 新增 `TelegramMessage`,字段对齐 `TelegramMessageEvent`,`receivedAt` 为系统时区 `java.time.LocalDateTime`。
- [x] 1.2 在 `telegram-python-subprocess-runtime` 新增 `TelegramClient` 接口,声明 `getState()`、`login()` → `LoginFlow`、`peekUnreadMessage(long chatId)`、`updateProxies(List<TelegramSessionProxyConfig>)`、`close()`。
- [x] 1.3 实现 `TelegramClient` 默认实现:构造接收手机号 + `TelegramSessionConfig` + `TelegramSession`(或 session 工厂),内部订阅 `session.getPublisher()`。
- [x] 1.4 在默认实现中实现 envelope 路由:`reply` → 匹配 `PendingReply`/`PendingFetch`;`message` 带 `replyInputId` 且属 collect disposition → 收集;实时 `message` 与 `log` → 仅记元数据日志(不含 `text`)。
- [x] 1.5 在默认实现中实现懒启动 `ensureRunning()`:synchronized、幂等,首次操作启动 `session.start(config)`,`FAILED` 后下次操作对进程级失败自动重启;新增显式 `start()` 供 manager 立即启动。
- [x] 1.6 新增 `LoginFlow`:与 client 共享状态,`getState()` 返回当前 `AuthorizationState`;`submit(String value)` 按 `WAIT_PHONE`/`WAIT_CODE`/`WAIT_PASSWORD` 多态分发对应命令,`READY` 时 submit 报错,返回提交后的新 `AuthorizationState`。
- [x] 1.7 新增 `PendingFetch`:按 `replyInputId` 收集 `List<TelegramMessage>`,以匹配 `reply` 为终止信号,带超时;与 `PendingReply` 并列。
- [x] 1.8 实现 `peekUnreadMessage(chatId)`:登记 inputId 的 disposition=collect,发 `fetch_unread` 命令,通过 `PendingFetch` 收集并返回 `List<TelegramMessage>`,`receivedAt` 按系统时区转 `LocalDateTime`。
- [x] 1.9 实现 `updateProxies(...)`:重建 `TelegramSessionConfig` 的代理链并重启 session,保持授权状态机延续。
- [x] 1.10 worker.py 把 `fetch_unread` 从 stub(`count:0`)实现为真实拉取:读取 `chatId` 与可选 `limit`,调 `get_chat_history(chat_id, limit=...)`,对每条消息输出带 `replyInputId` 的 `message` envelope(含 `messageId`),最后输出匹配 `replyInputId` 的 `reply`;`limit` 默认 `min(unreadCount, maxPerChat)`。

## 2. Step 1 — 测试与验证

- [x] 2.1 新增 `TelegramClient` 单元测试,使用 stub `TelegramSession`:覆盖 `getState` 反映 status reply。
- [x] 2.2 覆盖 `login()`→`LoginFlow`:按 `WAIT_*` 分发正确命令并返回新状态;`READY` 后 `submit` 报错;构造手机号进入 `start`。
- [x] 2.3 覆盖 `ensureRunning`:首次调启动、重复调 no-op、`FAILED` 后下次自动重启;并发首次调用只启动一次。
- [x] 2.4 覆盖 `peekUnreadMessage`:发 `fetch_unread`、收集匹配 `replyInputId` 的 `message` 直到 `reply`、返回 `List<TelegramMessage>`(含 `LocalDateTime` 转换);无消息返回空列表;超时处理。
- [x] 2.5 覆盖路由:实时 `message`(无 `replyInputId`)只记日志不入收集;`log` envelope 记日志。
- [x] 2.6 覆盖隐私:client 日志断言只含元数据(`chatId`/`chatType`/`senderId`/`senderUsername`/`receivedAt`),绝不含 `text`、验证码、密码、apiHash、代理密码。
- [x] 2.7 手动真机验证(沿用 README manual validation):建号 → `login` 走完流程 → `peekUnreadMessage` 拉取某 chat 未读,确认元数据完整、时间为本地时区。
- [x] 2.8 运行 `mvn test`、`telegram-python-subprocess-runtime` 下 Python helper 测试、`openspec validate`,确认 Step 1 通过且稳定。

> 2.7(真机验证)需由人执行。自动化部分(mvn test / Python 测试 / openspec validate)已全部通过。

## 3. Step 2 — starter 契约调整(依赖 Step 1 验证通过)

- [x] 3.1 `TelegramAccountSessionManager` 新增 `List<TelegramMessage> peekUnreadMessages(long accountId, long chatId)`。
- [x] 3.2 `TelegramAccountSessionManager` 移除消息 push 相关方法:`subscribe`、`publishTestMessage`、无返回值的 `scan`(`scan(TelegramScanRequest)` 与默认 `scan(long)` 一并移除)。
- [x] 3.3 保留 `subscribeStatus`(status 仍以命令回复回调形式扇出);确认 `TelegramAccountService.saveStatus` 链路不受影响。
- [x] 3.4 `TelegramMessage` 取代 `TelegramMessageEvent`:迁移所有引用;孤儿类型 `TelegramMessageEvent`/`TelegramMessageListener`/`TelegramScanRequest` 已删除。
- [x] 3.5 `InMemoryTelegramAccountSessionManager` 测试桩与新契约一致。

## 4. Step 2 — runtime manager 变薄(彻底断开直接操作 session)

- [x] 4.1 `PythonSubprocessTelegramAccountSessionManager` 重写为注册表:`Map<accountId, TelegramClient>`;组装 `TelegramSessionConfig` 并创建/缓存 client。
- [x] 4.2 各 starter 方法委托 client:`start`→`client.start()`、`stop`→`client.close()`、`submit*`→`client.submitAuthorization(...)`、`peekUnreadMessages`→`client.peekUnreadMessage`、`updateProxies`→`client.updateProxies`、`status`→`client.getState()`。
- [x] 4.3 移除 manager 直接持有 `TelegramSession`、订阅 `getPublisher()`、维护 `WorkerState` 路由逻辑的代码;协议路由、`PendingFetch`、envelope 构造全部下沉到 client,消除 Step 1 的重复。
- [x] 4.4 status 变更由 client 在命令回复时回调 manager,manager 扇出给 `subscribeStatus` 监听器。
- [x] 4.5 worker 资源解析、依赖检查、工作目录准备等 bootstrap 逻辑保留在 manager(服务于 client 的 `TelegramSessionConfig` 组装)。

## 5. Step 2 — control-server 通知改 pull

- [x] 5.1 `TelegramUnreadScanScheduler` 改 pull:对每个 `READY` 账号的每个监控 chatId 调 `peekUnreadMessages`,拿到 `List<TelegramMessage>`。
- [x] 5.2 scheduler 逐条把 `TelegramMessage` 喂给 `NotificationRuleService.handle`。
- [x] 5.3 `NotificationRuleService`:移除构造函数中的 `sessions.subscribe(this::handle)`;`handle` 签名改为接收 `TelegramMessage`;模板渲染中 `receivedAt` 直接用 `LocalDateTime` 格式化。
- [x] 5.4 `NotifiedTelegramMessageService` 的去重键(accountId/chatId/messageId)随 `TelegramMessage` 调整,语义不变。
- [x] 5.5 清理 control-server 中 `TelegramMessageEvent`、`TelegramMessageListener`、`scan` 相关残留引用;`TestMessageController` 改为直接调 `NotificationRuleService.handle`。

## 6. Step 2 — 验证与清理

- [x] 6.1 更新 `telegram-python-subprocess-runtime/README.md`:补充 `TelegramClient`/`LoginFlow`/`peekUnreadMessage` 业务层说明,以及 pull 驱动的通知模型。
- [x] 6.2 运行 `mvn test`,确认 runtime、starter、control-server 测试全部通过。
- [x] 6.3 运行 `openspec validate`,确认 change artifacts 完整、契约一致。
- [x] 6.4 手动端到端验证:scheduler 周期拉取未读 → 规则匹配 → Bark 推送,确认 pull 链路正常、延迟可接受。
- [x] 6.5 确认 `TelegramSession` 此后仅由 `TelegramClient` 使用(全局搜索无其他直接操作 session 的路径)。

> 6.4(端到端真机验证)需由人执行,是归档本 change 前的最后门禁。其余自动化验证(mvn test 全模块绿 / openspec validate / session 收口检查)已全部通过。
