package site.kael.telegram.notifier.core.model;

import java.util.List;

public record NotifiedMessageRecord(
        long accountId,
        long chatId,
        long messageId,
        String notifiedAt,
        List<Long> matchedRuleIds,
        List<DeliveryResultEntry> deliveryResults
) {
}
