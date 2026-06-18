CREATE TABLE IF NOT EXISTS administrators (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS telegram_accounts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    display_name TEXT NOT NULL,
    phone_number TEXT,
    enabled INTEGER NOT NULL DEFAULT 1,
    authorization_state TEXT NOT NULL DEFAULT 'LOGGED_OUT',
    active_proxy_id INTEGER,
    connection_error TEXT,
    scan_frequency_seconds INTEGER NOT NULL DEFAULT 60,
    unread_age_threshold_seconds INTEGER NOT NULL DEFAULT 3600,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS proxy_servers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    protocol TEXT NOT NULL,
    host TEXT NOT NULL,
    port INTEGER NOT NULL,
    username TEXT,
    password TEXT,
    enabled INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS account_proxies (
    account_id INTEGER NOT NULL REFERENCES telegram_accounts(id) ON DELETE CASCADE,
    proxy_id INTEGER NOT NULL REFERENCES proxy_servers(id) ON DELETE CASCADE,
    priority INTEGER NOT NULL,
    PRIMARY KEY (account_id, proxy_id)
);

CREATE TABLE IF NOT EXISTS push_channels (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    type TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    config_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS notification_rules (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    source_label TEXT NOT NULL,
    condition_json TEXT NOT NULL,
    template TEXT NOT NULL,
    channel_ids_json TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS message_stats (
    bucket TEXT NOT NULL,
    account_id INTEGER NOT NULL,
    message_count INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (bucket, account_id)
);

CREATE TABLE IF NOT EXISTS rule_stats (
    bucket TEXT NOT NULL,
    rule_id INTEGER NOT NULL,
    hit_count INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (bucket, rule_id)
);

CREATE TABLE IF NOT EXISTS delivery_stats (
    bucket TEXT NOT NULL,
    rule_id INTEGER NOT NULL,
    channel_id INTEGER NOT NULL,
    success_count INTEGER NOT NULL DEFAULT 0,
    failure_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    PRIMARY KEY (bucket, rule_id, channel_id)
);
