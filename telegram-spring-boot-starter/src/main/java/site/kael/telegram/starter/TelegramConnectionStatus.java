package site.kael.telegram.starter;

public record TelegramConnectionStatus(
        long accountId,
        AuthorizationState authorizationState,
        Long activeProxyId,
        String errorMessage
) {
}
