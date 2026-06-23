package site.kael.telegram.notifier.core.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import site.kael.telegram.notifier.core.model.AccountWorkerLog;

import java.time.Instant;
import java.util.List;

@Repository
public class AccountWorkerLogDao {
    private final JdbcTemplate jdbc;
    private final RowMapper<AccountWorkerLog> mapper;

    public AccountWorkerLogDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.mapper = (rs, rowNum) -> new AccountWorkerLog(
                rs.getLong("id"),
                rs.getLong("account_id"),
                rs.getString("level"),
                rs.getString("message"),
                Instant.parse(rs.getString("created_at"))
        );
    }

    public void insert(long accountId, String level, String message, String createdAt) {
        jdbc.update("""
                INSERT INTO account_worker_logs
                    (account_id, level, message, created_at)
                VALUES(?,?,?,?)
                """, accountId, level, message, createdAt);
    }

    public List<AccountWorkerLog> selectByAccountId(long accountId, int limit, int offset) {
        return jdbc.query("""
                SELECT * FROM account_worker_logs
                WHERE account_id = ?
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """, mapper, accountId, limit, offset);
    }

    public void deleteOldestBeyond(long accountId, int keep) {
        jdbc.update("""
                DELETE FROM account_worker_logs
                WHERE account_id = ? AND id NOT IN (
                    SELECT id FROM account_worker_logs
                    WHERE account_id = ?
                    ORDER BY created_at DESC
                    LIMIT ?
                )
                """, accountId, accountId, keep);
    }
}
