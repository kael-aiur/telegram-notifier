package site.kael.telegram.notifier.core.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import site.kael.telegram.notifier.core.model.TelegramAccount;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class TelegramAccountDao {
    private final JdbcTemplate jdbc;
    private final RowMapper<TelegramAccount> mapper;

    public TelegramAccountDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.mapper = (rs, rowNum) -> new TelegramAccount(
                rs.getLong("id"),
                rs.getString("display_name"),
                rs.getString("phone_number"),
                rs.getInt("enabled") == 1,
                rs.getString("authorization_state"),
                nullableLong(rs.getObject("active_proxy_id")),
                rs.getString("connection_error"),
                rs.getLong("scan_frequency_seconds"),
                rs.getLong("unread_age_threshold_seconds"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }

    public List<TelegramAccount> selectAll() {
        return jdbc.query("SELECT * FROM telegram_accounts ORDER BY id", mapper);
    }

    public Optional<TelegramAccount> selectById(long id) {
        return jdbc.query(
                "SELECT * FROM telegram_accounts WHERE id = ?", mapper, id)
                .stream().findFirst();
    }

    public List<TelegramAccount> selectByAuthorizationStateAndEnabled(String state, boolean enabled) {
        return jdbc.query(
                "SELECT * FROM telegram_accounts WHERE authorization_state = ? AND enabled = ?",
                mapper, state, enabled ? 1 : 0);
    }

    public long insert(String displayName, String phoneNumber, boolean enabled,
                       long scanFrequencySeconds, long unreadAgeThresholdSeconds,
                       String createdAt, String updatedAt) {
        jdbc.update("""
                INSERT INTO telegram_accounts
                    (display_name, phone_number, enabled, scan_frequency_seconds,
                     unread_age_threshold_seconds, created_at, updated_at)
                VALUES(?,?,?,?,?,?,?)
                """, displayName, phoneNumber, enabled ? 1 : 0,
                scanFrequencySeconds, unreadAgeThresholdSeconds, createdAt, updatedAt);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    public void update(long id, String displayName, String phoneNumber, boolean enabled,
                       long scanFrequencySeconds, long unreadAgeThresholdSeconds,
                       String updatedAt) {
        jdbc.update("""
                UPDATE telegram_accounts
                SET display_name = ?, phone_number = ?, enabled = ?,
                    scan_frequency_seconds = ?, unread_age_threshold_seconds = ?, updated_at = ?
                WHERE id = ?
                """, displayName, phoneNumber, enabled ? 1 : 0,
                scanFrequencySeconds, unreadAgeThresholdSeconds, updatedAt, id);
    }

    public void deleteById(long id) {
        jdbc.update("DELETE FROM telegram_accounts WHERE id = ?", id);
    }

    public void updateAuthorizationState(long id, String state, Long activeProxyId,
                                         String connectionError, String updatedAt) {
        jdbc.update("""
                UPDATE telegram_accounts
                SET authorization_state = ?, active_proxy_id = ?,
                    connection_error = ?, updated_at = ?
                WHERE id = ?
                """, state, activeProxyId, connectionError, updatedAt, id);
    }

    public void updateScanSettings(long id, long scanFrequencySeconds,
                                   long unreadAgeThresholdSeconds, String updatedAt) {
        jdbc.update("""
                UPDATE telegram_accounts
                SET scan_frequency_seconds = ?, unread_age_threshold_seconds = ?, updated_at = ?
                WHERE id = ?
                """, scanFrequencySeconds, unreadAgeThresholdSeconds, updatedAt, id);
    }

    public void updatePhoneNumber(long id, String phoneNumber, String updatedAt) {
        jdbc.update("""
                UPDATE telegram_accounts
                SET phone_number = ?, updated_at = ?
                WHERE id = ?
                """, phoneNumber, updatedAt, id);
    }

    private Long nullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
