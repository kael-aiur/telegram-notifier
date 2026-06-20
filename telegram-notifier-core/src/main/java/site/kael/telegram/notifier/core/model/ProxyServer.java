package site.kael.telegram.notifier.core.model;

import java.time.Instant;

public record ProxyServer(
        long id,
        String name,
        String protocol,
        String host,
        int port,
        String username,
        String password,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
    public ProxyServer masked() {
        return new ProxyServer(id, name, protocol, host, port, username,
                password == null ? null : "******", enabled, createdAt, updatedAt);
    }
}
