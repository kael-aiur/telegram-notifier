## ADDED Requirements

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

### Requirement: message envelope 必须包含 Telegram messageId
Every Telegram message envelope emitted by the Python worker SHALL include the Telegram message id in `content.messageId` when Telegram provides an id. This field MUST be stable for use by Java-side deduplication together with `accountId` and `chatId`.

#### Scenario: scan 返回未读消息 id
- **WHEN** worker emits a `message` envelope for an unread Telegram message returned by scan
- **THEN** the envelope content MUST include `messageId` equal to the Telegram message id

#### Scenario: 实时消息包含 id
- **WHEN** worker emits a live update `message` envelope for a Telegram message that has an id
- **THEN** the envelope content MUST include `messageId` equal to the Telegram message id
