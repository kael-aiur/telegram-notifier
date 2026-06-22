package site.kael.telegram.notifier.core.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import site.kael.telegram.notifier.core.model.ProxyServer;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class ProxyDao {
    private final JdbcTemplate jdbc;
    private final RowMapper<ProxyServer> serverMapper;

    public ProxyDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.serverMapper = (rs, rowNum) -> new ProxyServer(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("protocol"),
                rs.getString("host"),
                rs.getInt("port"),
                rs.getString("username"),
                rs.getString("password"),
                rs.getInt("enabled") == 1,
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }

    // ===== Proxy Server Operations =====

    public List<ProxyServer> selectAllServers() {
        return jdbc.query("SELECT * FROM proxy_servers ORDER BY id", serverMapper);
    }

    public Optional<ProxyServer> selectServerById(long id) {
        return jdbc.query(
                "SELECT * FROM proxy_servers WHERE id = ?", serverMapper, id)
                .stream().findFirst();
    }

    public long insertServer(String name, String protocol, String host, int port,
                             String username, String password, boolean enabled,
                             String createdAt, String updatedAt) {
        jdbc.update("""
                INSERT INTO proxy_servers
                    (name, protocol, host, port, username, password, enabled, created_at, updated_at)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, name, protocol, host, port, username, password, enabled ? 1 : 0,
                createdAt, updatedAt);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    public void updateServer(long id, String name, String protocol, String host, int port,
                             String username, String password, boolean enabled, String updatedAt) {
        jdbc.update("""
                UPDATE proxy_servers
                SET name = ?, protocol = ?, host = ?, port = ?,
                    username = ?, password = ?, enabled = ?, updated_at = ?
                WHERE id = ?
                """, name, protocol, host, port, username, password, enabled ? 1 : 0,
                updatedAt, id);
    }

    public void deleteServerById(long id) {
        jdbc.update("DELETE FROM proxy_servers WHERE id = ?", id);
    }

    public boolean isReferencedByAccounts(long proxyId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_proxies WHERE proxy_id = ?",
                Integer.class, proxyId);
        return count != null && count > 0;
    }

    // ===== Account-Proxy Binding Operations =====

    public List<Long> selectProxyIdsByAccountId(long accountId) {
        return jdbc.query(
                "SELECT proxy_id FROM account_proxies WHERE account_id = ? ORDER BY priority",
                (rs, rowNum) -> rs.getLong("proxy_id"), accountId);
    }

    public void deleteBindingsByAccountId(long accountId) {
        jdbc.update("DELETE FROM account_proxies WHERE account_id = ?", accountId);
    }

    public void insertBinding(long accountId, long proxyId, int priority) {
        jdbc.update(
                "INSERT INTO account_proxies(account_id, proxy_id, priority) VALUES(?,?,?)",
                accountId, proxyId, priority);
    }

    // ===== Join Query =====

    public List<ProxyServer> selectProxiesByAccountId(long accountId) {
        return jdbc.query("""
                SELECT p.* FROM proxy_servers p
                JOIN account_proxies ap ON ap.proxy_id = p.id
                WHERE ap.account_id = ?
                ORDER BY ap.priority
                """, serverMapper, accountId);
    }
}
