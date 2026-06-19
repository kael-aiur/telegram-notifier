CREATE TABLE IF NOT EXISTS notified_telegram_messages (
    account_id INTEGER NOT NULL,
    chat_id INTEGER NOT NULL,
    message_id INTEGER NOT NULL,
    notified_at TEXT NOT NULL,
    PRIMARY KEY (account_id, chat_id, message_id)
);
