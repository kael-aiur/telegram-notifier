package site.kael.telegram.notifier.core.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class AdminDao {
    private final JdbcTemplate jdbc;

    public AdminDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int count() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM administrators", Integer.class);
        return count != null ? count : 0;
    }

    public void insert(String username, String passwordHash, String createdAt) {
        jdbc.update(
                "INSERT INTO administrators(username, password_hash, created_at) VALUES(?,?,?)",
                username, passwordHash, createdAt);
    }

    public Optional<String> selectPasswordHashByUsername(String username) {
        return jdbc.query(
                        "SELECT password_hash FROM administrators WHERE username = ?",
                        (rs, rowNum) -> rs.getString("password_hash"), username)
                .stream().findFirst();
    }
}
