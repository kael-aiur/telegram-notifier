## MODIFIED Requirements

### Requirement: TelegramClient 单账号业务能力接口
`telegram-python-subprocess-runtime` SHALL expose a `TelegramClient` abstraction that wraps a single account's low-level subprocess session and exposes Telegram business capability in account-scoped terms. `TelegramClient` MUST provide `getState()` returning `site.kael.telegram.starter.AuthorizationState`, `login()` returning a `LoginFlow`, `peekUnreadMessage(long chatId)` returning `List<TelegramMessage>`, `updateProxies(List<site.kael.telegram.starter.ProxyConfig>)`, and `close()`. A `TelegramClient` MUST be assembled by a `TelegramClientFactory` from the account's business fields (account id, display name, phone number, api id, api hash, account data directory) together with process-level runtime parameters held by the factory; the subprocess session and its runtime config MUST remain internal to the runtime module and MUST NOT appear in the `TelegramClient` interface or `TelegramClientConfig`. `TelegramClient` MUST NOT require upper layers to start or stop the Python subprocess directly.

#### Scenario: 创建 client 并查询初始状态
- **WHEN** a `TelegramClient` is assembled by the factory from an account's business fields and process-level parameters but no operation has been invoked yet
- **THEN** `getState()` MUST return an `AuthorizationState` without the caller having started any subprocess

#### Scenario: 上层不直接操作 session
- **WHEN** an upper layer needs to read messages or drive login for an account
- **THEN** it MUST be able to do so entirely through `TelegramClient` methods without calling `TelegramSession.start`/`send`/`getPublisher` directly, and without referencing `TelegramSession`, `TelegramSessionConfig`, or `TelegramSessionProxyConfig`

### Requirement: updateProxies 与生命周期延续
`updateProxies(List<site.kael.telegram.starter.ProxyConfig>)` SHALL rebuild the runtime proxy chain and restart the wrapped subprocess session so the new chain takes effect, while preserving the account's authorization state machine continuity. `TelegramClient` SHALL internally convert the business-form `ProxyConfig` entries into the subprocess runtime proxy form; the `TelegramSessionProxyConfig` type MUST NOT be exposed through the `updateProxies` signature. After `updateProxies`, `getState()` MUST reflect the post-restart authorization state without requiring the upper layer to re-drive login unless authorization was actually lost.

#### Scenario: 更新代理后复用授权
- **WHEN** `updateProxies` is called on a `READY` account with a new `List<ProxyConfig>` proxy chain
- **THEN** the subprocess session MUST restart with the new chain and `getState()` MUST return `READY` if the stored session remains valid

#### Scenario: close 释放资源
- **WHEN** `close()` is called on a `TelegramClient`
- **THEN** the wrapped subprocess session MUST be stopped and the client MUST release its publisher subscription and pending command state

## ADDED Requirements

### Requirement: client 对外接口与配置的 session 封装边界
`telegram-python-subprocess-runtime` SHALL treat `TelegramSession`, `TelegramSessionConfig`, `TelegramSessionProxyConfig`, and `SubprocessTelegramSession` as internal implementation details of the runtime module. The `TelegramClient` interface, the `TelegramClientConfig` record, and the `TelegramClientFactory` interface MUST NOT expose any of these session types in their public method signatures, return types, or fields. `TelegramClientConfig` SHALL carry only account business fields (account id, display name, phone number, api id, api hash, account data directory, and the account proxy chain as `List<ProxyConfig>`) and MUST NOT embed a `TelegramSessionConfig`.

#### Scenario: client 接口与配置不含 session 类型
- **WHEN** the `TelegramClient` interface and `TelegramClientConfig` record are inspected by an upper layer (the account session manager or control-server)
- **THEN** none of their method signatures, return types, or fields MUST reference `TelegramSession`, `TelegramSessionConfig`, `TelegramSessionProxyConfig`, or `SubprocessTelegramSession`

#### Scenario: 注册表不直接装配 session
- **WHEN** the account session manager creates a `TelegramClient` for an account
- **THEN** it MUST obtain the client from `TelegramClientFactory.create` using business-form inputs and MUST NOT directly instantiate `SubprocessTelegramSession` or assemble a `TelegramSessionConfig`

### Requirement: client 工厂装配与 bootstrap
`telegram-python-subprocess-runtime` SHALL expose a `TelegramClientFactory` that assembles single-account `TelegramClient` instances and encapsulates all subprocess-session construction and worker bootstrap. The factory SHALL hold the process-level runtime parameters (Python executable, worker script, working directory, extra process arguments) together with the shared `ObjectMapper` and log sanitizer. `TelegramClientFactory.create(TelegramAccountConfig accountConfig, java.util.function.Consumer<TelegramConnectionStatus> statusListener)` MUST internally assemble the account's `TelegramClientConfig` (combining the business fields from `accountConfig` with the global api id/hash and the per-account data directory), construct the underlying subprocess session, assemble the subprocess runtime config internally, and return a ready-to-use `TelegramClient` without exposing session types to the caller. The factory SHALL perform worker bootstrap (worker script resolution and Python dependency validation) and MUST surface bootstrap or assembly failure to the account session manager so that account `start` can return an `ERROR` `TelegramConnectionStatus` rather than throwing.

#### Scenario: 工厂用业务配置产出 client
- **WHEN** the account session manager calls `factory.create` with a `TelegramAccountConfig` carrying the account's business fields and proxy chain
- **THEN** the factory MUST internally assemble the `TelegramClientConfig` and subprocess session, return a ready-to-use `TelegramClient`, and the caller MUST NOT be required to provide a `TelegramClientConfig`, `TelegramSessionConfig`, or any session type

#### Scenario: bootstrap 失败返回错误状态
- **WHEN** worker bootstrap detects a missing Python dependency or an invalid worker script path while the account session manager is starting an account
- **THEN** the manager MUST be able to obtain the failure from the factory and return an `ERROR` `TelegramConnectionStatus` for that account instead of propagating an exception

#### Scenario: 工厂 bootstrap 仅执行一次
- **WHEN** multiple accounts are started and each triggers factory client creation
- **THEN** worker script resolution and Python dependency validation MUST be performed at most once and their results reused across subsequent client creations
