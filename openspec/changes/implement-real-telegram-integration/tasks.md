## 1. 配置与依赖

- [x] 1.1 调研并确认 Telegram Java client 依赖方案，记录 Maven 坐标、native 依赖和本地运行要求。
- [x] 1.2 在 `telegram-spring-boot-starter` 中新增 Telegram client 配置属性，覆盖 mode、api-id、api-hash、data-dir 和运行时选择。
- [x] 1.3 调整 starter 自动配置，让 starter 只提供公共 API/内存实现，真实运行时由独立模块提供 `TelegramAccountSessionManager` bean。
- [x] 1.4 为真实模式配置缺失或数据目录不可写场景增加启动账号时的可诊断错误返回。

## 2. Python 子进程 Telegram 运行时

- [x] 2.1 新增 `telegram-python-subprocess-runtime` 模块和每账号 worker 状态对象，管理进程引用、授权状态、活动代理、错误信息和会话目录。
- [x] 2.2 实现 `start`，为账号创建或重启 Python worker，并初始化账号隔离的数据目录。
- [x] 2.3 实现 `stop`，关闭账号 Python worker、释放运行资源并返回 `LOGGED_OUT` 状态。
- [x] 2.4 实现 `status`，返回 Python worker 最近的授权状态、活动代理和错误信息。
- [ ] 2.5 保留 `InMemoryTelegramAccountSessionManager` 作为默认模式和测试替身。
- [ ] 2.6 定义 Java 与 Python worker 的 JSON Lines 命令/事件协议。

## 3. 授权流程

- [ ] 3.1 将 Telegram client 授权状态映射到现有 `AuthorizationState`。
- [ ] 3.2 实现手机号提交，将 `submitPhone` 转发到真实 Telegram client。
- [ ] 3.3 实现验证码提交，将 `submitCode` 转发到真实 Telegram client 并处理 `READY`、`WAIT_PASSWORD` 和错误状态。
- [ ] 3.4 实现二步验证密码提交，将 `submitPassword` 转发到真实 Telegram client。
- [ ] 3.5 为授权输入错误保留可继续输入的状态，并设置 `connection_error` 可读诊断信息。

## 4. 代理链应用

- [ ] 4.1 将 `ProxyConfig` 的 HTTP、HTTPS、SOCKS5 类型映射到真实 Telegram client 的代理配置。
- [ ] 4.2 在 `start` 时按账号绑定优先级选择第一个启用代理并更新 activeProxyId。
- [ ] 4.3 实现 `updateProxies`，对运行中账号应用新代理链。
- [ ] 4.4 在底层客户端无法热切换代理时执行受控重连，并保留账号会话目录和授权状态。
- [ ] 4.5 确保代理密码不进入应用日志或错误消息。

## 5. 消息事件桥接

- [ ] 5.1 监听真实 Telegram 新消息事件，并提取聊天、发送者、接收时间和文本。
- [ ] 5.2 将文本消息转换为 `TelegramMessageEvent` 并发布给已注册 listener。
- [ ] 5.3 对无法提取文本的消息执行跳过或空文本事件策略，保证 session 不异常退出。
- [ ] 5.4 增加测试，确认真实事件桥接不会向统计、投递记录或日志持久化消息正文。

## 6. 控制服务同步与前端体验

- [ ] 6.1 新增连接状态变更 listener 或等价机制，让 control server 持久化异步 Telegram 状态变化。
- [ ] 6.2 调整 `TelegramAccountService`，在真实运行时错误、授权变更和代理变更后正确保存账号状态。
- [ ] 6.3 前端展示真实模式下的配置缺失、等待手机号、等待验证码、等待密码和已就绪状态。
- [ ] 6.4 优化账号 Start 流程：如果账号已有手机号且运行时要求手机号，自动提交手机号或给出明确操作提示。

## 7. 验证与文档

- [ ] 7.1 增加 starter 单元测试，覆盖配置校验、状态映射、代理选择和授权输入错误。
- [ ] 7.2 增加 control server 集成测试，覆盖账号启动、状态持久化和代理绑定刷新。
- [ ] 7.3 增加前端测试，覆盖 401 处理、账号状态展示和真实登录操作提示。
- [ ] 7.4 运行 `mvn test` 和前端 `npm test`、`npm run build`。
- [ ] 7.5 补充运行说明，描述如何申请 Telegram `api_id` / `api_hash`、配置真实模式、设置数据目录和回退到 memory 模式。
