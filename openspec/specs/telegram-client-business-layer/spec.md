## Purpose
Defines the single-account Telegram business capability layer `TelegramClient` that wraps the low-level `TelegramSession`, exposing authorization state, login flow, unread message peek, proxy update, and lifecycle in business terms while shielding upper layers from the Python subprocess and the JSON Lines protocol. Also defines the `LoginFlow` handle, the `TelegramMessage` return type, lazy subprocess startup, envelope routing by command disposition, and the privacy boundary for client-side logging.

## Requirements
### Requirement: TelegramClient 单账号业务能力接口
`telegram-python-subprocess-runtime` SHALL expose a `TelegramClient` abstraction that wraps a single account's `TelegramSession` and exposes Telegram business capability in account-scoped terms. `TelegramClient` MUST provide `getState()` returning `site.kael.telegram.starter.AuthorizationState`, `login()` returning a `LoginFlow`, `peekUnreadMessage(long chatId)` returning `List<TelegramMessage>`, `updateProxies(List<TelegramSessionProxyConfig>)`, and `close()`. A `TelegramClient` MUST be constructed with the account phone number and the runtime `TelegramSessionConfig` it will use to start the wrapped session. `TelegramClient` MUST NOT require upper layers to start or stop the Python subprocess directly.

#### Scenario: 创建 client 并查询初始状态
- **WHEN** a `TelegramClient` is constructed for a phone number and runtime config but no operation has been invoked yet
- **THEN** `getState()` MUST return an `AuthorizationState` without the caller having started any subprocess

#### Scenario: 上层不直接操作 session
- **WHEN** an upper layer needs to read messages or drive login for an account
- **THEN** it MUST be able to do so entirely through `TelegramClient` methods without calling `TelegramSession.start`/`send`/`getPublisher` directly

### Requirement: 懒启动 ensureRunning
`TelegramClient` SHALL lazily start the wrapped `TelegramSession` on demand. The first business operation (`getState` after first contact, `login`, `peekUnreadMessage`, etc.) MUST start the subprocess using the constructed `TelegramSessionConfig` if it is not already running. Subsequent operations while the session is running MUST be no-ops with respect to startup. If the session has reached a process-level failed state, the next operation MUST restart it. `ensureRunning()` MUST be idempotent under concurrent invocation: concurrent first callers MUST cause the subprocess to start exactly once. Authorization-level errors (for example an incorrect verification code) MUST NOT trigger an automatic restart; only process-level failures (subprocess not alive) MAY be auto-recovered.

#### Scenario: 首次操作触发启动
- **WHEN** a business operation is invoked and the wrapped session is not yet running
- **THEN** `TelegramClient` MUST start the session with its constructed config before processing the operation

#### Scenario: 已运行不重复启动
- **WHEN** a business operation is invoked and the wrapped session is already running
- **THEN** `TelegramClient` MUST NOT start a new subprocess

#### Scenario: 进程级失败后自动重启
- **WHEN** the subprocess has exited unexpectedly and a business operation is invoked
- **THEN** `TelegramClient` MUST restart the subprocess before processing the operation

#### Scenario: 授权级失败不自动重启
- **WHEN** `getState()` returns `AuthorizationState.ERROR` due to an authorization error such as a wrong verification code
- **THEN** `TelegramClient` MUST NOT silently restart; the upper layer MUST be able to observe the state and decide to re-enter the login flow

#### Scenario: 并发首次调用只启动一次
- **WHEN** multiple threads invoke business operations concurrently before the session has started
- **THEN** the subprocess MUST be started exactly once

### Requirement: LoginFlow 共享状态与多态提交
`login()` SHALL return a `LoginFlow` that shares the same authorization state as the `TelegramClient`. `LoginFlow.getState()` MUST return the current `AuthorizationState`. `LoginFlow.submit(String value)` MUST dispatch to the correct protocol command based on the current state: `WAIT_PHONE` submits the phone, `WAIT_CODE` submits the verification code, `WAIT_PASSWORD` submits the two-step password, and `submit` on `READY` MUST signal an error. `submit` MUST return the `AuthorizationState` resulting from the submission. The login flow MUST NOT maintain a separate copy of state; it MUST read and update the client's shared state.

#### Scenario: 提交手机号后等待验证码
- **WHEN** the flow is in `WAIT_PHONE` and `submit` is called with a phone number
- **THEN** the client MUST send the phone submission command and `submit` MUST return the resulting `AuthorizationState` (typically `WAIT_CODE`)

#### Scenario: 提交验证码后授权完成
- **WHEN** the flow is in `WAIT_CODE` and `submit` is called with an accepted verification code
- **THEN** `submit` MUST return `READY`

#### Scenario: 已登录后提交报错
- **WHEN** the flow is in `READY` and `submit` is called
- **THEN** the call MUST signal an error instead of sending a submission command

#### Scenario: 流程与 client 共享状态
- **WHEN** a `submit` advances the shared authorization state
- **THEN** `TelegramClient.getState()` MUST observe the same advanced state as `LoginFlow.getState()`

### Requirement: peekUnreadMessage 拉取未读消息
`peekUnreadMessage(long chatId)` SHALL return the unread messages of the specified chat as a `List<TelegramMessage>`. It MUST send a `fetch_unread` input command scoped to the chat, collect every `message` envelope whose `replyInputId` matches that input until the matching `reply` arrives, and convert each collected message into a `TelegramMessage`. The collection MUST terminate on the matching `reply` or on a timeout. A chat with no unread messages MUST yield an empty list. The returned messages MUST be ordered consistently with the worker output.

#### Scenario: 拉取到未读消息
- **WHEN** `peekUnreadMessage` is called for a chat that has unread messages
- **THEN** it MUST return a non-empty `List<TelegramMessage>` containing one element per message envelope collected for that input

#### Scenario: 无未读消息返回空列表
- **WHEN** `peekUnreadMessage` is called for a chat with no unread messages
- **THEN** it MUST return an empty list

#### Scenario: 收集以匹配 reply 为终止信号
- **WHEN** the worker emits multiple `message` envelopes and then a `reply` for the same `replyInputId`
- **THEN** `peekUnreadMessage` MUST collect exactly those messages and return after the `reply`

### Requirement: 按命令 disposition 路由 message envelope
`TelegramClient` SHALL route `message` envelopes by the disposition of the command that triggered them. For a peek command (collect disposition), `message` envelopes carrying the command's `replyInputId` MUST be collected into that command's result and MUST NOT be forwarded elsewhere. Real-time `message` envelopes without a `replyInputId`, and any `message` envelopes whose command disposition is not collect, MUST be handled as passive observations (logged as metadata only) and MUST NOT be collected into a peek result.

#### Scenario: peek 命令的消息进入收集
- **WHEN** a `message` envelope arrives with a `replyInputId` belonging to an in-flight peek command
- **THEN** it MUST be added to that peek's collected list

#### Scenario: 实时消息不进入收集
- **WHEN** a `message` envelope arrives without a `replyInputId`
- **THEN** it MUST be logged as metadata only and MUST NOT appear in any peek result

### Requirement: TelegramMessage 返回类型
`telegram-spring-boot-starter` SHALL define `TelegramMessage` as the element type returned by `peekUnreadMessage`. `TelegramMessage` MUST carry the complete message metadata available from the worker: `accountId`, `chatId`, `messageId`, `chatTitle`, `chatType`, `senderId`, `senderName`, `senderUsername`, `receivedAt`, and `text`. `receivedAt` MUST be a `java.time.LocalDateTime` expressed in the system default time zone, converted from the worker-provided instant. The remaining fields MUST align with the existing `TelegramMessageEvent` field set.

#### Scenario: 返回完整元数据
- **WHEN** `peekUnreadMessage` returns a message for which the worker provided all metadata fields
- **THEN** the `TelegramMessage` MUST expose `accountId`, `chatId`, `messageId`, `chatTitle`, `chatType`, `senderId`, `senderName`, `senderUsername`, `receivedAt`, and `text`

#### Scenario: 时间为系统时区 LocalDateTime
- **WHEN** the worker emits `receivedAt` as a UTC instant
- **THEN** the `TelegramMessage.receivedAt` MUST be the corresponding `LocalDateTime` in the system default time zone

### Requirement: client 端日志隐私边界
`TelegramClient` SHALL log only message metadata when observing message or log envelopes. It MUST NOT write Telegram message `text`, verification codes, two-step passwords, proxy passwords, or API hash values to any log. Message `text` MAY exist only inside `TelegramMessage` in-memory objects, peek results returned to callers, and `type=message` envelope content in transit.

#### Scenario: 记录消息仅含元数据
- **WHEN** `TelegramClient` observes a `message` envelope and logs it
- **THEN** the log entry MUST contain metadata such as chat id, chat type, sender id, sender username, and received time, and MUST NOT contain the message `text`

#### Scenario: 日志不泄漏验证码或密码
- **WHEN** `TelegramClient` handles an authorization error or logs a diagnostic
- **THEN** the log MUST NOT contain any submitted verification code, two-step password, proxy password, or API hash

### Requirement: updateProxies 与生命周期延续
`updateProxies(List<TelegramSessionProxyConfig>)` SHALL rebuild the runtime proxy chain and restart the wrapped session so the new chain takes effect, while preserving the account's authorization state machine continuity. After `updateProxies`, `getState()` MUST reflect the post-restart authorization state without requiring the upper layer to re-drive login unless authorization was actually lost.

#### Scenario: 更新代理后复用授权
- **WHEN** `updateProxies` is called on a `READY` account with a new proxy chain
- **THEN** the session MUST restart with the new chain and `getState()` MUST return `READY` if the stored session remains valid

#### Scenario: close 释放资源
- **WHEN** `close()` is called on a `TelegramClient`
- **THEN** the wrapped `TelegramSession` MUST be stopped and the client MUST release its publisher subscription and pending command state
