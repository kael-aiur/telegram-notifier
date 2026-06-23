-- Account worker logs: capture Python worker log output per account

CREATE TABLE account_worker_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL REFERENCES telegram_accounts(id) ON DELETE CASCADE,
    level TEXT NOT NULL DEFAULT 'INFO',
    message TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX idx_worker_logs_account_id ON account_worker_logs(account_id, created_at DESC);
