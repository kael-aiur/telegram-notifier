package site.kael.telegram.notifier.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record NotificationRule(
        long id,
        String name,
        boolean enabled,
        String sourceLabel,
        Map<String, Object> condition,
        String template,
        List<Long> channelIds,
        Instant createdAt,
        Instant updatedAt
) {
}
