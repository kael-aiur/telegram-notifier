## Context

第一阶段（已归档的 `telegram-client-business-layer`）引入了 `DefaultTelegramClient` 作为单账号业务能力层，`PythonSubprocessTelegramAccountSessionManager` 也已改造为持有 `Map<accountId, DefaultTelegramClient>` 的薄注册表。但 `TelegramSession` 相关概念仍从三处泄漏：

1. `TelegramClient.updateProxies(List<TelegramSessionProxyConfig>)` —— 接口签名暴露 session 代理类型。
2. `TelegramClientConfig.sessionConfig()` —— 配置 record 内嵌整个 `TelegramSessionConfig`。
3. `PythonSubprocessTelegramAccountSessionManager` —— `createClient()` 直接 `new SubprocessTelegramSession`、`sessionConfig()` 组装 `TelegramSessionConfig`、并持有 worker bootstrap（`resolveWorkerScript`/`validatePythonDependencies`/`WORKER_RESOURCES`/路径计算）。

约束：
- control-server 业务代码与 starter 公共接口 `TelegramAccountSessionManager` 是稳定边界，不能改。
- 底层 `TelegramSession`/`TelegramSessionConfig`/`SubprocessTelegramSession` 作为 runtime 模块内部抽象**保留**（不删除），只是不再外泄。
- manager.`start()` 配置无效时返回 `ERROR` `TelegramConnectionStatus`（而非抛异常）的契约不能破。

## Goals / Non-Goals

**Goals:**
- `TelegramSession`/`TelegramSessionConfig`/`TelegramSessionProxyConfig`/`SubprocessTelegramSession` 成为 runtime 模块实现细节，从 client 对外接口、client 配置、manager 中全部消除。
- 引入 `TelegramClientFactory` 封装 subprocess session 创建 + worker bootstrap + 进程级参数。
- manager 降为纯业务路由：注入工厂、`create(业务配置)`、委托 client。

**Non-Goals:**
- 不删除/重命名底层 `TelegramSession` 抽象（runtime 内部实现保留）。
- 不改 starter 公共接口 `TelegramAccountSessionManager` 的方法签名或语义。
- 不改 control-server 业务代码。
- 不改 Python worker 协议或 worker 端 Python 代码。
- 不新增 `TelegramClient` 的公共能力（`login`/`peekUnreadMessage` 等语义不变）。

## Decisions

### D1: `updateProxies` 入参复用 starter `ProxyConfig`，不新建中性 DTO
- **选择**：`updateProxies(List<site.kael.telegram.starter.ProxyConfig>)`，复用 starter 业务形态；`ProxyConfig → session 代理` 的转换下沉到 client 内部。
- **理由**：client 本就是"业务能力层"，runtime 模块已依赖 starter；`ProxyConfig` 已是代理的业务表达。
- **备选**：定义 client 层中性 DTO（如 `TelegramProxy`）——拒绝，字段与 `ProxyConfig` 一一对应，重复。
- **备选**：保留 `TelegramSessionProxyConfig`——拒绝，正是本次要消除的泄漏。

### D2: `TelegramClientConfig` 仅留业务字段，进程级参数归工厂
- **选择**：删除 `sessionConfig` 字段，保留 `accountId`/`displayName`/`phoneNumber`/`apiId`/`apiHash`/`dataDir`。进程级参数（executable/workerScript/workingDirectory/extraArgs/超时）由工厂从 `PythonTelegramRuntimeProperties` 持有。
- **理由**：进程级参数是全局的、跨账号共享的运行时基础设施，不属于单账号业务配置；放工厂天然共享，避免每账号 config 重复携带。
- **影响**：`DefaultTelegramClient` 内部需持有进程级配置（工厂 `create()` 时注入），供 `updateProxies` 时 rebuild subprocess config。
- **备选**：把进程参数平铺进 `TelegramClientConfig`——拒绝，配置臃肿且每账号重复。

### D3: 超时参数处理
- **现状**：`startupTimeout`/`shutdownTimeout` 硬编码 `Duration.ofSeconds(10/4)` 在 `manager.sessionConfig()`。
- **选择**：随 `sessionConfig` 一起下沉到工厂，保持当前默认值（是否提升为 `PythonTelegramRuntimeProperties` 配置项留作实现期小决策，不阻塞 spec）。

### D4: 工厂形态——Spring bean，惰性 bootstrap
- **选择**：`TelegramClientFactory` 接口 + 实现，在 `PythonTelegramRuntimeAutoConfiguration` 注册为 bean；manager 注入。worker bootstrap（脚本解析 + 依赖检查）在首次 `validate()`/`create()` 时执行一次并缓存结果，**不在 bean 构造时执行**。
- **理由**：bootstrap 有进程开销（依赖检查会 spawn python），且非 `PYTHON_SUBPROCESS` 模式下不应触发；惰性执行避免拖垮应用启动。工厂是单例 bean，天然持有 bootstrap 缓存结果。manager 不再持有 `resolvedWorkerScript`/`dependencyValidationError`。
- **备选**：bean 构造时立即 bootstrap——拒绝，会导致非 python 模式或 Python 不可用时整个 runtime bean 初始化失败。

### D5: bootstrap 失败的 ERROR 契约——工厂 `validate()` 预检
- **现状**：`manager.start()` 配置无效时返回 `ERROR` `TelegramConnectionStatus`（不抛）。
- **选择**：工厂暴露 `validate()` 预检方法返回错误字符串（null 表示通过），与现状 `validateConfiguration()` 形态一致；manager.`start()` 先 `factory.validate()`，失败则返回 `ERROR` status，再 `factory.create()`。
- **理由**：manager 改动最小，避免用异常控制流；bootstrap 结果在工厂内已固定，`validate()` 只读无竞态。
- **备选**：`create()` 抛异常由 manager 捕获——拒绝，用异常表达"配置缺失"这种预期路径不优雅。

### D6: 账号数据目录创建下沉工厂
- **选择**：`accountDirectory(accountId)`（`dataDir/accounts/{id}`）创建随 `sessionConfig` 组装下沉到工厂 `create()`；失败由 manager 转 `ERROR` status。
- **理由**：账号目录路径与 `sessionConfig` 的 `runtimeDataDirectory` 同源（都用 `dataDirectory()`），拆开会造成路径计算重复。

## Risks / Trade-offs

- **[client 内部持有进程级配置，`updateProxies` rebuild 逻辑更隐蔽]** → 在 `DefaultTelegramClient` 内部以明确私有字段持有进程级配置，保留 `updateProxies` rebuild 的单元测试覆盖。
- **[工厂 bootstrap 若在 bean 构造时执行，Python 不可用会阻断应用启动]** → 采用 D4 惰性 bootstrap：工厂构造只记录属性，首次 `validate()`/`create()` 执行并缓存；非 python 模式不受影响。
- **[测试改造量]** → `DefaultTelegramClientTest` 与 `ManagerTest` 当前直接 `new SubprocessTelegramSession`/`new TelegramSessionConfig`，改为经工厂或 client 新构造；按 TDD 先改测试再改实现，保证行为不回归。
- **[MODIFIED requirement 措辞去掉 `TelegramSession` 类型名]** → 内部实现仍用 `TelegramSession`，spec 措辞改用"subprocess session"以体现封装；实现期需保证 `close`/`updateProxies` 的内部 session 操作语义不变。

## Migration Plan

- 纯内部重构，无数据迁移、无配置项破坏（`telegram.client.python.*` 不变）。
- 实施顺序：先建工厂（接口+实现+bean 注册）→ 改 client 接口/配置/`DefaultTelegramClient` 实现 → 改 manager（注入工厂）→ 改测试 → `mvn test` 全绿。
- 回滚：control-server/starter 不受影响，整组改动可在单次提交内完成并直接 revert，无外部连带。

## Open Questions

- D3：超时参数是否需要提升为 `PythonTelegramRuntimeProperties` 配置项？（倾向：暂不，保持硬编码默认；实现期再定）
- D5：`validate()` 预检 vs `create()` 抛异常——实现期最终敲定（倾向 `validate()` 预检，已在 D5 给出理由）。
