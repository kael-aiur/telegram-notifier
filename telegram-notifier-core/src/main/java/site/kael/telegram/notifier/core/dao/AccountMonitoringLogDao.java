package site.kael.telegram.notifier.core.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import site.kael.telegram.notifier.core.model.AccountMonitoringLog;

import java.time.Instant;
import java.util.List;

@Repository
public class AccountMonitoringLogDao {
    private final JdbcTemplate jdbc;
    private final RowMapper<AccountMonitoringLog> mapper;

    public AccountMonitoringLogDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.mapper = (rs, rowNum) -> new AccountMonitoringLog(
                rs.getLong("id"),
                rs.getLong("account_id"),
                rs.getLong("chat_id"),
                Instant.parse(rs.getString("scanned_at")),
                rs.getInt("unread_count"),
                rs.getInt("notified_count"),
                Instant.parse(rs.getString("created_at"))
        );
    }

    public void insert(long accountId, long chatId, String scannedAt,
                       int unreadCount, int notifiedCount, String createdAt) {
        jdbc.update("""
                INSERT INTO account_monitoring_logs
                    (account_id, chat_id, scanned_at, unread_count, notified_count, created_at)
                VALUES(?,?,?,?,?,?)
                """, accountId, chatId, scannedAt, unreadCount, notifiedCount, createdAt);
    }

    public List<AccountMonitoringLog> selectByAccountId(long accountId, int limit, int offset) {
        return jdbc.query("""
                SELECT * FROM account_monitoring_logs
                WHERE account_id = ?
                ORDER BY scanned_at DESC
                LIMIT ? OFFSET ?
                """, mapper, accountId, limit, offset);
    }

    public void deleteOldestBeyond(long accountId, int keep) {
        jdbc.update("""
                DELETE FROM account_monitoring_logs
                WHERE account_id = ? AND id NOT IN (
                    SELECT id FROM account_monitoring_logs
                    WHERE account_id = ?
                    ORDER BY scanned_at DESC
                    LIMIT ?
                )
                """, accountId, accountId, keep);
    }
}
