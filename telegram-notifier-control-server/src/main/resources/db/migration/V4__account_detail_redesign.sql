-- Account detail redesign: monitored chatIds, per-account rules, notification records, monitoring logs

-- 1. telegram_accounts: add monitored chatIds
ALTER TABLE telegram_accounts ADD COLUMN monitored_chat_ids_json TEXT;

-- 2. notification_rules: add account_id (nullable at DB level, enforced non-null by application layer)
ALTER TABLE notification_rules ADD COLUMN account_id INTEGER;

-- 3. notified_telegram_messages: add matched rules and delivery results
ALTER TABLE notified_telegram_messages ADD COLUMN matched_rule_ids_json TEXT;
ALTER TABLE notified_telegram_messages ADD COLUMN delivery_results_json TEXT;

-- 4. account_monitoring_logs: per-account scan activity log
CREATE TABLE account_monitoring_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL REFERENCES telegram_accounts(id) ON DELETE CASCADE,
    chat_id INTEGER NOT NULL,
    scanned_at TEXT NOT NULL,
    unread_count INTEGER NOT NULL,
    notified_count INTEGER NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX idx_monitoring_logs_account_id ON account_monitoring_logs(account_id, scanned_at DESC);
