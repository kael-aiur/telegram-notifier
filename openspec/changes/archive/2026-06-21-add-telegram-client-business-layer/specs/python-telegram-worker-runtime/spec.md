## Purpose
增量需求:为 Python Telegram worker 增加真实 `fetch_unread` 业务命令,支撑 `TelegramClient.peekUnreadMessage`。现有 `fetch_unread` 仅为返回 `count:0` 的 stub,本变更将其实现为按 chatId 拉取未读消息的完整命令。

## ADDED Requirements
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
