package site.kael.telegram.notifier.core.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class StatisticsDao {
    private final JdbcTemplate jdbc;

    public StatisticsDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void incrementMessageCount(String bucket, long accountId) {
        jdbc.update("""
                INSERT INTO message_stats(bucket, account_id, message_count) VALUES(?,?,1)
                ON CONFLICT(bucket, account_id) DO UPDATE SET message_count = message_count + 1
                """, bucket, accountId);
    }

    public void incrementRuleHitCount(String bucket, long ruleId) {
        jdbc.update("""
                INSERT INTO rule_stats(bucket, rule_id, hit_count) VALUES(?,?,1)
                ON CONFLICT(bucket, rule_id) DO UPDATE SET hit_count = hit_count + 1
                """, bucket, ruleId);
    }

    public void upsertDeliveryStats(String bucket, long ruleId, long channelId,
                                    int successDelta, int failureDelta, String lastError) {
        jdbc.update("""
                INSERT INTO delivery_stats
                    (bucket, rule_id, channel_id, success_count, failure_count, last_error)
                VALUES(?,?,?,?,?,?)
                ON CONFLICT(bucket, rule_id, channel_id) DO UPDATE SET
                    success_count = success_count + excluded.success_count,
                    failure_count = failure_count + excluded.failure_count,
                    last_error = excluded.last_error
                """, bucket, ruleId, channelId, successDelta, failureDelta, lastError);
    }

    public List<Map<String, Object>> selectAllMessageStats() {
        return jdbc.queryForList(
                "SELECT * FROM message_stats ORDER BY bucket DESC, account_id");
    }

    public List<Map<String, Object>> selectAllRuleStats() {
        return jdbc.queryForList(
                "SELECT * FROM rule_stats ORDER BY bucket DESC, rule_id");
    }

    public List<Map<String, Object>> selectAllDeliveryStats() {
        return jdbc.queryForList(
                "SELECT * FROM delivery_stats ORDER BY bucket DESC, rule_id, channel_id");
    }
}
