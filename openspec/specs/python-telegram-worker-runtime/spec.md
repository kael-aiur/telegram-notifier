## Purpose
Defines the Python subprocess Telegram worker runtime, including the Java session abstraction, subprocess lifecycle, startup, session reuse, authorization commands, proxy mapping, JSON Lines envelope protocol, events, and sensitive data handling.

## Requirements
### Requirement: TelegramSession 子进程交互接口
`telegram-python-subprocess-runtime` SHALL expose a Java `TelegramSession` abstraction for low-level Python subprocess interaction. `TelegramSession` MUST provide `start(config)`, `send(String str)`, `java.util.concurrent.Flow.Publisher<String> getPublisher()`, `stop()`, and `getStatus()`. The abstraction MUST NOT expose Telegram authorization state, account business operations, or message event domain objects directly.

#### Scenario: 启动 Python 子进程
- **WHEN** Java code calls `TelegramSession.start(config)` with valid runtime configuration
- **THEN** the implementation MUST start the configured Python subprocess and transition the session lifecycle status toward running state

#### Scenario: 发送原始 JSON 输入
- **WHEN** Java code calls `TelegramSession.send(str)` with a valid JSON envelope string
- **THEN** the implementation MUST write that string as one JSON Lines entry to the Python subprocess stdin

#### Scenario: 发布原始 JSON 输出
- **WHEN** the Python subprocess emits stdout or stderr output that is converted to protocol output
- **THEN** `TelegramSession.getPublisher()` MUST publish JSON envelope strings without converting them to Telegram business objects

### Requirement: SessionStatus 表达子进程生命周期
`SessionStatus` SHALL represent the Python subprocess lifecycle only. It MUST distinguish subprocess lifecycle conditions such as not started, starting, running, stopping, stopped, and failed. It MUST NOT reuse or encode Telegram authorization states such as `WAIT_CODE`, `READY`, or `LOGGED_OUT`.

#### Scenario: Python 进程启动成功
- **WHEN** the Python subprocess is started and ready for stdin/stdout interaction
- **THEN** `getStatus()` MUST report a running lifecycle state rather than a Telegram account authorization state

#### Scenario: Python 进程异常退出
- **WHEN** the Python subprocess exits unexpectedly
- **THEN** `getStatus()` MUST report a failed lifecycle state independently from any previous Telegram authorization state

### Requirement: TelegramSession runtime 配置边界
`TelegramSession.start(config)` SHALL accept only Python runtime configuration needed to run the subprocess and establish network connectivity. The config MUST support proxy configuration and MAY include process executable, worker script, working directory, runtime data directory, extra process arguments, environment variables, and lifecycle timeouts. It MUST NOT contain Telegram business command fields such as verification code, password, chat id, or unread message fetch parameters.

#### Scenario: 使用代理启动 runtime
- **WHEN** Java starts a `TelegramSession` with proxy configuration in the runtime config
- **THEN** the Python subprocess MUST receive or otherwise apply the proxy configuration as runtime connectivity configuration before Telegram network operations depend on it

#### Scenario: 业务字段不属于 runtime config
- **WHEN** Java needs to submit a verification code or fetch unread messages for a chat
- **THEN** those values MUST be sent through `send(String str)` as `type=input` protocol content instead of being placed in `start(config)`

### Requirement: Envelope 输入输出协议
Java and Python SHALL communicate through JSON Lines envelopes. Every Java input and every Python output published by the session MUST be a JSON object containing `id`, `type`, and `content`; outputs MAY contain `replyInputId`. Java-to-Python protocol currently MUST support `type=input`. Python-to-Java protocol currently MUST support `type=reply`, `type=log`, and `type=message`, and MUST allow future type extension without changing the envelope shape.

#### Scenario: Java 发送 input envelope
- **WHEN** Java sends a protocol message to Python
- **THEN** the JSON line MUST contain `type` equal to `input`, an `id`, and a `content` value containing the actual command payload

#### Scenario: Python 发布 reply envelope
- **WHEN** Python completes handling an input command
- **THEN** it MUST publish a `type=reply` JSON line with its own `id`, the original input id in `replyInputId`, and completion details in `content`

#### Scenario: Python 发布 log envelope
- **WHEN** Python or the Java subprocess wrapper needs to publish diagnostic output
- **THEN** it MUST publish a `type=log` JSON envelope rather than plain text through the session publisher

#### Scenario: Python 发布 message envelope
- **WHEN** Python emits Telegram message data
- **THEN** it MUST publish a `type=message` JSON envelope with message fields inside `content`

### Requirement: replyInputId 关联输入与输出
Python outputs that are direct results of a Java input SHALL use `replyInputId` to identify the triggering input. A `reply` output MUST include `replyInputId`. A `message` output MUST include `replyInputId` when it is produced as part of a fetch or scan input, and MUST omit `replyInputId` when it is a real-time asynchronous Telegram message.

#### Scenario: 命令完成响应关联输入
- **WHEN** Python emits a `reply` for input id `java-1`
- **THEN** the reply envelope MUST contain `replyInputId` equal to `java-1`

#### Scenario: 实时消息不关联输入
- **WHEN** Python receives a new Telegram message from its live update handler
- **THEN** it MUST emit a `type=message` envelope without `replyInputId`

#### Scenario: 拉取消息关联输入并以 reply 结束
- **WHEN** Python handles an input that fetches unread messages for a chat
- **THEN** every fetched message envelope MUST contain that input id as `replyInputId`, and Python MUST emit a final `type=reply` envelope with the same `replyInputId` to mark the input complete

### Requirement: scan 命令按 Java 提供的 chatIds 采集当前未读会话候选消息
Python Telegram worker SHALL support a `scan` business command inside a `type=input` envelope. The scan command MUST accept the Java-provided `accountId` and `chatIds`, use Pyrogram dialog unread state to find matching chats, and emit at most one `type=message` envelope per chat that currently has unread messages. The emitted message SHOULD be the latest chat history message returned by `get_chat_history(chat_id, limit=1)`. In command-driven scan mode, worker MUST NOT register a live message handler that emits messages outside the Java-provided scan range.

#### Scenario: scan 返回指定会话的未读候选消息
- **WHEN** worker receives a valid `scan` input with `chatIds` containing a chat that currently has unread messages
- **THEN** worker MUST emit a `type=message` envelope for that chat's top message candidate

#### Scenario: scan 不返回非指定会话消息
- **WHEN** worker receives a `scan` input with a finite `chatIds` list
- **THEN** worker MUST NOT emit messages from chats that are not included in the input `chatIds`

#### Scenario: scan 会话没有未读消息
- **WHEN** worker receives a `scan` input for a chat whose messages are already read by the account
- **THEN** worker MUST emit no `type=message` envelope for that chat

### Requirement: scan 输出必须关联输入并以 reply 结束
Message envelopes produced by a scan SHALL include `replyInputId` equal to the triggering Java input id, and the worker MUST emit a final `type=reply` envelope with the same `replyInputId` after scan processing completes.

#### Scenario: scan 产生多条消息后完成
- **WHEN** worker handles scan input `java-1` and returns multiple unread messages
- **THEN** every emitted `message` envelope MUST contain `replyInputId` equal to `java-1`, and a final `reply` envelope MUST also contain `replyInputId` equal to `java-1`

#### Scenario: scan 没有未读消息也完成
- **WHEN** worker handles scan input and finds no unread messages
- **THEN** worker MUST emit a successful `reply` envelope for that input

### Requirement: worker 不执行通知业务过滤
Python Telegram worker SHALL NOT apply notification business decisions while handling scan. It MUST NOT evaluate unread age thresholds, notification rules, push channel routing, or already-notified message memory.

#### Scenario: 未读候选消息未达到 Java 阈值
- **WHEN** a scanned chat contains a current unread top message candidate whose age is below the Java account threshold
- **THEN** worker MAY still emit that candidate message, leaving age filtering to Java and preserving future scan opportunities while the chat remains unread

#### Scenario: 未读消息不匹配任何 Java 规则
- **WHEN** a scanned chat contains a currently unread message that would not match any Java notification rule
- **THEN** worker MAY still emit that unread message, leaving rule filtering to Java

### Requirement: Worker 启动配置
Python Telegram worker SHALL initialize real Telegram client operations from command content received through the JSON Lines envelope protocol, not from legacy top-level command fields. The worker MUST use runtime configuration supplied by `TelegramSession.start(config)` for Python process execution and connectivity, including proxy configuration, and MUST use business input content for account-level values such as `accountId`, `apiId`, `apiHash`, `dataDir`, and `phoneNumber`. Worker MUST write all account runtime files into the account `dataDir`, and MUST NOT write them into the project directory, implicit current working directory, or system temporary directories.

#### Scenario: 配置完整时初始化真实客户端
- **WHEN** worker receives a `type=input` envelope whose `content` requests Telegram account start with valid `apiId`, `apiHash`, and writable `dataDir`
- **THEN** worker MUST create the account data directory and initialize the real Telegram client using that directory

#### Scenario: 数据目录不可写
- **WHEN** worker receives a Telegram account start input whose `dataDir` cannot be created or written
- **THEN** worker MUST output a `type=reply` envelope with `replyInputId` matching the input and an error in `content`, and MUST NOT create a real Telegram connection

### Requirement: Session 持久化
Python Telegram worker SHALL 为每个账号使用隔离的 Telegram session 文件，并在服务或 worker 重启后复用已有有效 session。Session 文件 MUST 位于账号 `dataDir` 下。

#### Scenario: 已有有效 session
- **WHEN** worker 启动时账号 `dataDir` 中存在有效 Telegram session
- **THEN** worker MUST 复用该 session 并输出 `READY` 状态

#### Scenario: 无有效 session 且无手机号
- **WHEN** worker 启动时没有有效 Telegram session 且 `phoneNumber` 为空
- **THEN** worker MUST 输出 `WAIT_PHONE` 状态

#### Scenario: 无有效 session 且有手机号
- **WHEN** worker 启动时没有有效 Telegram session 且 `phoneNumber` 非空
- **THEN** worker MUST 向 Telegram 请求验证码并输出 `WAIT_CODE` 状态

### Requirement: 授权命令处理
Python Telegram worker SHALL support authorization business commands carried inside `type=input` envelope `content`, including phone submission, verification code submission, and two-step password submission. The worker MUST map Telegram authorization results to existing `AuthorizationState` names in reply content or business status content handled by the Java adapter. Authorization input errors MUST preserve a state that allows the user to continue input and MUST expose readable diagnostics without leaking the submitted secret.

#### Scenario: 提交手机号成功
- **WHEN** worker is in `WAIT_PHONE` state and receives an input envelope containing a valid phone submission command
- **THEN** worker MUST request a Telegram verification code and output a reply or status content indicating `WAIT_CODE`

#### Scenario: 提交验证码后授权成功
- **WHEN** worker is in `WAIT_CODE` state and receives an input envelope containing a Telegram-accepted verification code command
- **THEN** worker MUST output content indicating `READY` and begin listening for new messages

#### Scenario: 提交验证码后需要二步验证
- **WHEN** worker is in `WAIT_CODE` state and receives an input envelope whose verification code triggers two-step verification
- **THEN** worker MUST output content indicating `WAIT_PASSWORD`

#### Scenario: 验证码错误
- **WHEN** worker is in `WAIT_CODE` state and receives an input envelope containing a Telegram-rejected verification code
- **THEN** worker MUST preserve or re-output `WAIT_CODE` and include an error diagnostic that does not leak the verification code

#### Scenario: 提交二步验证密码成功
- **WHEN** worker is in `WAIT_PASSWORD` state and receives an input envelope containing a Telegram-accepted two-step password command
- **THEN** worker MUST output content indicating `READY` and begin listening for new messages

#### Scenario: 二步验证密码错误
- **WHEN** worker is in `WAIT_PASSWORD` state and receives an input envelope containing a Telegram-rejected two-step password
- **THEN** worker MUST preserve or re-output `WAIT_PASSWORD` and include an error diagnostic that does not leak the password

### Requirement: 代理映射与活动代理
Python Telegram worker SHALL use the proxy configuration supplied as runtime connectivity configuration for Telegram client network access. It MUST select the first usable proxy in Java-provided order, map HTTP, HTTPS, and SOCKS5 proxy entries to Telegram client supported proxy configuration, and return the actually used `activeProxyId` through envelope output content. Proxy passwords MUST NOT appear in stdout, stderr, publisher output, reply content, log content, or Java logs.

#### Scenario: 使用第一个代理
- **WHEN** runtime configuration contains one or more enabled proxies
- **THEN** worker MUST prefer the first proxy for Telegram connectivity and expose that proxy id as `activeProxyId` in protocol output content

#### Scenario: 无代理启动
- **WHEN** runtime configuration contains no enabled proxy
- **THEN** worker MUST use direct connectivity and output content without `activeProxyId`

#### Scenario: 代理配置不受支持
- **WHEN** worker receives a proxy protocol or proxy configuration that the Telegram client cannot use
- **THEN** worker MUST output an error reply or log diagnostic that does not contain the proxy password

### Requirement: JSON Lines 协议事件
Python Telegram worker SHALL write only JSON Lines envelope objects to stdout. The session publisher MUST publish only JSON envelope strings. Plain diagnostics MUST NOT pollute the stdout protocol stream; diagnostic output MUST be represented as `type=log` envelope output, including Java-side wrapping of stderr when necessary. Protocol outputs MUST at least support `reply`, `log`, and `message`.

#### Scenario: 输出响应事件
- **WHEN** worker finishes handling an input command
- **THEN** worker MUST output a valid JSON `reply` envelope with matching `replyInputId`

#### Scenario: 输出诊断日志
- **WHEN** worker needs to output ordinary diagnostics
- **THEN** worker MUST output a valid JSON `log` envelope through the protocol stream or allow Java to wrap stderr as a sanitized `log` envelope

#### Scenario: 输出消息事件
- **WHEN** worker emits Telegram message data
- **THEN** worker MUST output a valid JSON `message` envelope

### Requirement: 新消息监听
Python Telegram worker SHALL listen for real Telegram new messages after the account reaches `READY` and normalize each message into a `type=message` JSON Lines envelope. Real-time message envelopes MUST NOT include `replyInputId`. Message `content` MUST at least include `accountId`, `chatId`, `chatTitle`, `chatType`, `senderId`, `senderName`, `senderUsername`, `receivedAt`, and `text` fields when those values are available.

#### Scenario: 收到文本消息
- **WHEN** a ready account receives a new Telegram text message through live updates
- **THEN** worker MUST output a `message` envelope without `replyInputId` and with text and source metadata in `content`

#### Scenario: 收到 caption 消息
- **WHEN** a ready account receives a Telegram message with caption through live updates
- **THEN** worker MUST put the caption in `content.text` of a `message` envelope

#### Scenario: 收到非文本消息
- **WHEN** a ready account receives a Telegram message from which text or caption cannot be extracted
- **THEN** worker MUST output a `message` envelope whose `content.text` is an empty string and MUST NOT exit the worker

### Requirement: message envelope 必须包含 Telegram messageId
Every Telegram message envelope emitted by the Python worker SHALL include the Telegram message id in `content.messageId` when Telegram provides an id. This field MUST be stable for use by Java-side deduplication together with `accountId` and `chatId`.

#### Scenario: scan 返回未读消息 id
- **WHEN** worker emits a `message` envelope for an unread Telegram message returned by scan
- **THEN** the envelope content MUST include `messageId` equal to the Telegram message id

#### Scenario: 实时消息包含 id
- **WHEN** worker emits a live update `message` envelope for a Telegram message that has an id
- **THEN** the envelope content MUST include `messageId` equal to the Telegram message id

### Requirement: 停止与资源释放
Python Telegram worker SHALL stop the Telegram client, release event listener resources, and exit cleanly when the subprocess is stopped or when it receives a stop business input. Stop responses emitted for a stop input MUST use a `reply` envelope with matching `replyInputId`. Stdin closure or parent process termination MUST cause the worker to release resources on a best-effort basis.

#### Scenario: 收到停止输入
- **WHEN** worker receives a `type=input` envelope containing a stop command
- **THEN** worker MUST stop the Telegram client, output a final `reply` envelope with matching `replyInputId`, and exit the process

#### Scenario: stdin 关闭
- **WHEN** Java closes worker stdin or parent process termination causes stdin to end
- **THEN** worker MUST best-effort stop the Telegram client and exit the process

### Requirement: 敏感信息保护
Python Telegram worker and Java subprocess runtime MUST NOT write Telegram message text, verification codes, two-step passwords, proxy passwords, or API hash values to persistent logs, stderr diagnostics, stdout error messages, publisher log envelopes, reply error content, database records, or status error messages. Telegram message text MAY appear only inside `type=message` envelope content and in explicit in-memory business processing that consumes such messages.

#### Scenario: 授权失败诊断
- **WHEN** Telegram rejects a verification code or two-step password
- **THEN** worker MUST output an error diagnostic that does not contain the original user input

#### Scenario: 代理连接失败诊断
- **WHEN** Telegram client connection fails while using a password-protected proxy
- **THEN** worker MUST output diagnostics that do not contain the proxy password

#### Scenario: 消息处理异常
- **WHEN** worker fails while handling a Telegram message
- **THEN** worker MUST output diagnostics that do not contain the message body text

### Requirement: fetch_unread 命令按 chatId 拉取未读消息
Python Telegram worker SHALL support a `fetch_unread` business command carried inside a `type=input` envelope `content`. The command MUST accept a `chatId` and an optional `limit`. Worker MUST fetch chat history for that chat using `get_chat_history(chat_id, limit=...)`, and for each fetched message MUST emit a `type=message` envelope carrying `replyInputId` equal to the triggering input id and including `messageId` plus the full normalized message fields (`accountId`, `chatId`, `messageId`, `chatTitle`, `chatType`, `senderId`, `senderName`, `senderUsername`, `receivedAt`, `text`). After emitting zero or more such messages, worker MUST emit a final `type=reply` envelope with the same `replyInputId` to mark the input complete. The effective limit SHOULD be bounded (for example `min(unreadCount, maxPerChat)`) to avoid fetching unbounded history. The command MUST NOT mark messages as read; it is a peek-only operation.

#### Scenario: 拉取指定会话的未读消息
- **WHEN** worker receives a valid `fetch_unread` input for a chat that currently has unread messages
- **THEN** worker MUST emit one `type=message` envelope per fetched message, each with `replyInputId` equal to the input id and a populated `messageId`, followed by a final `reply` with the same `replyInputId`

#### Scenario: 无未读消息也完成
- **WHEN** worker receives a `fetch_unread` input for a chat with no unread messages
- **THEN** worker MUST emit no `message` envelope and MUST emit a successful `reply` with the matching `replyInputId`

#### Scenario: 仅返回指定会话消息
- **WHEN** worker receives a `fetch_unread` input scoped to a single `chatId`
- **THEN** worker MUST emit messages only for that chat and MUST NOT emit messages from other chats

#### Scenario: 不标记消息已读
- **WHEN** worker handles a `fetch_unread` input
- **THEN** worker MUST NOT mark the fetched messages as read on the Telegram account
