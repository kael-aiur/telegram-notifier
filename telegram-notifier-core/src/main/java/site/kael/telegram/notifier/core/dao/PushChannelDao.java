package site.kael.telegram.notifier.core.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import site.kael.telegram.notifier.core.model.PushChannel;
import site.kael.telegram.notifier.core.support.JsonSupport;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class PushChannelDao {
    private final JdbcTemplate jdbc;
    private final JsonSupport json;
    private final RowMapper<PushChannel> mapper;

    public PushChannelDao(JdbcTemplate jdbc, JsonSupport json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, rowNum) -> new PushChannel(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("type"),
                rs.getInt("enabled") == 1,
                json.readMap(rs.getString("config_json")),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }

    public List<PushChannel> selectAll() {
        return jdbc.query("SELECT * FROM push_channels ORDER BY id", mapper);
    }

    public Optional<PushChannel> selectById(long id) {
        return jdbc.query(
                "SELECT * FROM push_channels WHERE id = ?", mapper, id)
                .stream().findFirst();
    }

    public long insert(String name, String type, boolean enabled,
                       Map<String, Object> config, String createdAt, String updatedAt) {
        jdbc.update("""
                INSERT INTO push_channels(name, type, enabled, config_json, created_at, updated_at)
                VALUES(?,?,?,?,?,?)
                """, name, type, enabled ? 1 : 0, json.write(config), createdAt, updatedAt);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    public void update(long id, String name, String type, boolean enabled,
                       Map<String, Object> config, String updatedAt) {
        jdbc.update("""
                UPDATE push_channels
                SET name = ?, type = ?, enabled = ?, config_json = ?, updated_at = ?
                WHERE id = ?
                """, name, type, enabled ? 1 : 0, json.write(config), updatedAt, id);
    }

    public void deleteById(long id) {
        jdbc.update("DELETE FROM push_channels WHERE id = ?", id);
    }
}
