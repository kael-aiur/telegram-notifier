package site.kael.telegram.python;

public record TelegramSessionProxyConfig(
        Long id,
        String protocol,
        String host,
        int port,
        String username,
        String password
) {
}
