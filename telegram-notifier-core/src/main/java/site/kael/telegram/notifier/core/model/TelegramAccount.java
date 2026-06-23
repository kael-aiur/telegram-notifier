package site.kael.telegram.notifier.core.model;

import java.time.Instant;
import java.util.List;

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
        boolean running,
        List<Long> monitoredChatIds,
        Instant createdAt,
        Instant updatedAt
) {
}
