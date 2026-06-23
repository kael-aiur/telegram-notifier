package site.kael.telegram.notifier.core.model;

public record DeliveryResultEntry(
        long ruleId,
        long channelId,
        boolean success,
        String message
) {
}
