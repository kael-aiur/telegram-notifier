package site.kael.telegram.starter;

public record ProxyConfig(
        long id,
        ProxyProtocol protocol,
        String host,
        int port,
        String username,
        String password,
        boolean enabled
) {
}
