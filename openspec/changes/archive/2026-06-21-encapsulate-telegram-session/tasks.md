## 1. 新建 client 工厂（封装 session 创建与 bootstrap）

- [x] 1.1 新建 `TelegramClientFactory` 接口：定义 `TelegramClient create(TelegramClientConfig config)` 与 `String validate()`（null 表示通过）
- [x] 1.2 新建 `DefaultTelegramClientFactory` 实现：持有 `PythonTelegramRuntimeProperties`、`TelegramClientProperties`、`ObjectMapper`、日志脱敏函数；惰性执行 worker bootstrap（`resolveWorkerScript` + `validatePythonDependencies`），结果缓存，不在构造时触发
- [x] 1.3 实现 `create()`：内部 `new SubprocessTelegramSession`、组装 `TelegramSessionConfig`、创建账号数据目录（`dataDir/accounts/{id}`），把 session 与进程级配置注入 `DefaultTelegramClient` 内部后返回，不向调用方暴露 session 类型
- [x] 1.4 实现 `validate()`：返回 bootstrap/装配错误字符串（null 通过），供 manager 转 `ERROR` `TelegramConnectionStatus`
- [x] 1.5 在 `PythonTelegramRuntimeAutoConfiguration` 注册 `TelegramClientFactory` bean

## 2. client 接口与配置去 session 化

- [x] 2.1 `TelegramClient.updateProxies` 签名改为 `updateProxies(List<site.kael.telegram.starter.ProxyConfig>)`，移除 `TelegramSessionProxyConfig`
- [x] 2.2 `TelegramClientConfig` 删除 `sessionConfig` 字段，仅保留 `accountId`/`displayName`/`phoneNumber`/`apiId`/`apiHash`/`dataDir`
- [x] 2.3 `DefaultTelegramClient` 改为内部持有进程级配置（由工厂 `create()` 注入），`updateProxies` 内部完成 `ProxyConfig` → session 代理转换并 rebuild subprocess 配置
- [x] 2.4 `DefaultTelegramClient` 构造参数调整：不再从 `TelegramClientConfig.sessionConfig()` 取进程配置，改为显式接收工厂注入的进程级配置

## 3. manager 瘦身为纯业务路由

- [x] 3.1 `PythonSubprocessTelegramAccountSessionManager` 注入 `TelegramClientFactory`，删除 `createClient`/`sessionConfig`/`sessionProxyPayload`/路径计算方法
- [x] 3.2 `start()` 改为：`factory.validate()` → 失败返回 `ERROR` status → `factory.create(业务配置)` → `client.start()`
- [x] 3.3 `updateProxies()` 改为直接 `client.updateProxies(List<ProxyConfig>)`，移除 `sessionProxyPayload` 转换
- [x] 3.4 删除 `WORKER_RESOURCES`/`resolvedWorkerScript`/`dependencyValidationError` 等 bootstrap 残留字段与方法

## 4. 测试改造（先改测试再改实现，保证行为不回归）

- [x] 4.1 `DefaultTelegramClientTest`：改用工厂或 client 新构造方式创建被测对象，移除直接 `new SubprocessTelegramSession`/`new TelegramSessionConfig`
- [x] 4.2 `PythonSubprocessTelegramAccountSessionManagerTest`：改用注入的工厂（或测试替身），验证 manager 不再直接装配 session
- [x] 4.3 新增 `DefaultTelegramClientFactoryTest`：覆盖 `validate()` 失败返回错误、`create()` 产出可用 client、bootstrap 仅执行一次

## 5. 验证

- [x] 5.1 `mvn test` 全绿
- [x] 5.2 grep 验证：control-server 与 starter 接口 `TelegramAccountSessionManager` 零改动
- [x] 5.3 grep 验证：`TelegramClient`/`TelegramClientConfig`/`TelegramClientFactory` 对外签名不含 `TelegramSession`/`TelegramSessionConfig`/`TelegramSessionProxyConfig`/`SubprocessTelegramSession`
