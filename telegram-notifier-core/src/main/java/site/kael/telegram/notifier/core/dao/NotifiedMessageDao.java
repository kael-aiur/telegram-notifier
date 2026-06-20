package site.kael.telegram.notifier.core.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NotifiedMessageDao {
    private final JdbcTemplate jdbc;

    public NotifiedMessageDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(long accountId, long chatId, long messageId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM notified_telegram_messages
                WHERE account_id = ? AND chat_id = ? AND message_id = ?
                """, Integer.class, accountId, chatId, messageId);
        return count != null && count > 0;
    }

    public void insert(long accountId, long chatId, long messageId, String notifiedAt) {
        jdbc.update("""
                INSERT OR IGNORE INTO notified_telegram_messages
                    (account_id, chat_id, message_id, notified_at)
                VALUES(?,?,?,?)
                """, accountId, chatId, messageId, notifiedAt);
    }
}
