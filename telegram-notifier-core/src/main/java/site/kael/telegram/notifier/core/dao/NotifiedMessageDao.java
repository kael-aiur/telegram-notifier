package site.kael.telegram.notifier.core.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import site.kael.telegram.notifier.core.model.NotifiedMessageRecord;
import site.kael.telegram.notifier.core.support.JsonSupport;

import java.util.List;

@Repository
public class NotifiedMessageDao {
    private final JdbcTemplate jdbc;
    private final JsonSupport json;
    private final RowMapper<NotifiedMessageRecord> mapper;

    public NotifiedMessageDao(JdbcTemplate jdbc, JsonSupport json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, rowNum) -> new NotifiedMessageRecord(
                rs.getLong("account_id"),
                rs.getLong("chat_id"),
                rs.getLong("message_id"),
                rs.getString("notified_at"),
                json.readLongList(rs.getString("matched_rule_ids_json")),
                json.readDeliveryResults(rs.getString("delivery_results_json"))
        );
    }

    public boolean exists(long accountId, long chatId, long messageId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM notified_telegram_messages
                WHERE account_id = ? AND chat_id = ? AND message_id = ?
                """, Integer.class, accountId, chatId, messageId);
        return count != null && count > 0;
    }

    public void insert(long accountId, long chatId, long messageId, String notifiedAt,
                       String matchedRuleIdsJson, String deliveryResultsJson) {
        jdbc.update("""
                INSERT OR IGNORE INTO notified_telegram_messages
                    (account_id, chat_id, message_id, notified_at,
                     matched_rule_ids_json, delivery_results_json)
                VALUES(?,?,?,?,?,?)
                """, accountId, chatId, messageId, notifiedAt,
                matchedRuleIdsJson, deliveryResultsJson);
    }

    public List<NotifiedMessageRecord> selectByAccountId(long accountId, int limit, int offset) {
        return jdbc.query("""
                SELECT * FROM notified_telegram_messages
                WHERE account_id = ?
                ORDER BY notified_at DESC
                LIMIT ? OFFSET ?
                """, mapper, accountId, limit, offset);
    }
}
