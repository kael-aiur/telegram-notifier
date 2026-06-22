package site.kael.telegram.notifier.core.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import site.kael.telegram.notifier.core.model.NotificationRule;
import site.kael.telegram.notifier.core.support.JsonSupport;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class NotificationRuleDao {
    private final JdbcTemplate jdbc;
    private final JsonSupport json;
    private final RowMapper<NotificationRule> mapper;

    public NotificationRuleDao(JdbcTemplate jdbc, JsonSupport json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, rowNum) -> new NotificationRule(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getInt("enabled") == 1,
                rs.getString("source_label"),
                json.readMap(rs.getString("condition_json")),
                rs.getString("template"),
                json.readLongList(rs.getString("channel_ids_json")),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }

    public List<NotificationRule> selectAll() {
        return jdbc.query("SELECT * FROM notification_rules ORDER BY id", mapper);
    }

    public Optional<NotificationRule> selectById(long id) {
        return jdbc.query(
                "SELECT * FROM notification_rules WHERE id = ?", mapper, id)
                .stream().findFirst();
    }

    public long insert(String name, boolean enabled, String sourceLabel,
                       Map<String, Object> condition, String template,
                       List<Long> channelIds, String createdAt, String updatedAt) {
        jdbc.update("""
                INSERT INTO notification_rules
                    (name, enabled, source_label, condition_json, template,
                     channel_ids_json, created_at, updated_at)
                VALUES(?,?,?,?,?,?,?,?)
                """, name, enabled ? 1 : 0, sourceLabel,
                json.write(condition), template, json.write(channelIds),
                createdAt, updatedAt);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    public void update(long id, String name, boolean enabled, String sourceLabel,
                       Map<String, Object> condition, String template,
                       List<Long> channelIds, String updatedAt) {
        jdbc.update("""
                UPDATE notification_rules
                SET name = ?, enabled = ?, source_label = ?, condition_json = ?,
                    template = ?, channel_ids_json = ?, updated_at = ?
                WHERE id = ?
                """, name, enabled ? 1 : 0, sourceLabel,
                json.write(condition), template, json.write(channelIds),
                updatedAt, id);
    }

    public void deleteById(long id) {
        jdbc.update("DELETE FROM notification_rules WHERE id = ?", id);
    }

    public boolean isChannelReferenced(long channelId) {
        // channel_ids_json is a JSON array like [1,2,3]
        // Use SQLite json_each to check membership
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_rules, json_each(notification_rules.channel_ids_json) WHERE json_each.value = ?",
                Integer.class, channelId);
        return count != null && count > 0;
    }
}
