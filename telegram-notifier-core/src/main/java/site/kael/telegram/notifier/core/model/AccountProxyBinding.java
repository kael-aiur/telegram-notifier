package site.kael.telegram.notifier.core.model;

public record AccountProxyBinding(
        long accountId,
        long proxyId,
        int priority
) {
}
