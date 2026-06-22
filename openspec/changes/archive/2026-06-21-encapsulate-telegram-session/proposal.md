## Why

第一阶段引入了 `TelegramClient` 业务层，但 `TelegramSession` 相关概念仍从三处泄漏到 client 对外接口与配置中：client 接口 `updateProxies(List<TelegramSessionProxyConfig>)` 直接暴露 session 代理类型、client 配置 `TelegramClientConfig.sessionConfig()` 内嵌整个 `TelegramSessionConfig`、以及 `PythonSubprocessTelegramAccountSessionManager` 直接 `new SubprocessTelegramSession` 并持有 worker bootstrap。这迫使多账号注册表（manager）感知底层子进程/协议配置细节，业务边界不清晰，session 概念无法作为 runtime 模块的纯实现细节被封装。

## What Changes

- 新增 `TelegramClientFactory`（`telegram-python-subprocess-runtime` 模块，Spring bean），封装 `SubprocessTelegramSession` 创建、`TelegramSessionConfig` 组装、worker bootstrap（worker 脚本解析 + Python 依赖检查），并持有进程级参数（executable/workerScript/workingDirectory/extraArgs）。
- **BREAKING**（runtime 模块内部 API）：`TelegramClient.updateProxies` 签名由 `List<TelegramSessionProxyConfig>` 改为 `List<ProxyConfig>`（复用 starter 业务形态），`ProxyConfig` 到 session 代理的转换下沉到 client 内部。
- **BREAKING**（runtime 模块内部 API）：`TelegramClientConfig` 移除 `sessionConfig` 字段，仅保留业务字段（accountId/displayName/phoneNumber/apiId/apiHash/dataDir）；进程级参数改由工厂持有，`create()` 时连同 session 注入 client 实现内部。
- 瘦身 `PythonSubprocessTelegramAccountSessionManager`：删除 `createClient`/`sessionConfig`/`sessionProxyPayload`/worker bootstrap/路径计算等 session 相关逻辑，改为注入 `TelegramClientFactory` + `factory.create(业务配置)`；保留 `start()` 在配置无效时返回 `ERROR` `TelegramConnectionStatus`（而非抛异常）的现有契约。
- control-server 业务代码与 starter 接口 `TelegramAccountSessionManager` 零改动（`updateProxies(long, List<ProxyConfig>)` 本就是业务形态）。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `telegram-client-business-layer`:
  - 修改「TelegramClient 单账号业务能力接口」：`updateProxies` 入参类型去 session 化；client 构造改为由工厂用业务配置加内部进程级参数装配，不再以 `TelegramSessionConfig` 暴露给上层。
  - 修改「updateProxies 与生命周期延续」：方法签名改为 `updateProxies(List<ProxyConfig>)`，代理重建与 session 重启语义不变。
  - 新增「client 对外接口与配置的 session 封装边界」：`TelegramClient` 接口、`TelegramClientConfig`、client 工厂的对外契约 MUST NOT 暴露 `TelegramSession`/`TelegramSessionConfig`/`TelegramSessionProxyConfig` 类型。
  - 新增「client 工厂装配与 bootstrap」：工厂封装 session 创建与 worker bootstrap，`create()` 接收业务配置返回 client，并定义 bootstrap/装配失败时返回 `ERROR` `TelegramConnectionStatus` 的契约。

## Impact

- **代码**：
  - `telegram-python-subprocess-runtime`：新增 `TelegramClientFactory`（接口 + 实现 + 在 `PythonTelegramRuntimeAutoConfiguration` 注册 bean）；改造 `TelegramClient`、`TelegramClientConfig`、`DefaultTelegramClient`（`updateProxies` 内部转换、内部持有进程级配置）；瘦身 `PythonSubprocessTelegramAccountSessionManager`（注入工厂）。
  - `telegram-notifier-control-server`：零改动。
  - `telegram-spring-boot-starter`：公共接口零改动。
- **测试**：`DefaultTelegramClientTest`、`PythonSubprocessTelegramAccountSessionManagerTest` 当前直接 `new SubprocessTelegramSession` / `new TelegramSessionConfig`，需改为通过工厂或 client 新的构造方式创建被测对象。
- **API/依赖**：所改 client 接口是 runtime 模块内部 API（非 starter 公共边界），control-server 不直接消费，manager 同步改造，故无跨模块影响。
- **数据库/配置**：无 schema 变更；进程级参数仍由 `telegram.client.python.*` 配置项提供，仅持有者从 manager 迁移到工厂。
