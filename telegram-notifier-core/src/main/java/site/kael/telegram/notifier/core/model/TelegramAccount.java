package site.kael.telegram.notifier.core.model;

import java.time.Instant;

public record TelegramAccount(
        long id,
        String displayName,
        String phoneNumber,
        boolean enabled,
        String authorizationState,
        Long activeProxyId,
        String connectionError,
        long scanFrequencySeconds,
        long unreadAgeThresholdSeconds,
        Instant createdAt,
        Instant updatedAt
) {
}
