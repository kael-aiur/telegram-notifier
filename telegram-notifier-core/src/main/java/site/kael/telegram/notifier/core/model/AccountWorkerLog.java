package site.kael.telegram.notifier.core.model;

import java.time.Instant;

public record AccountWorkerLog(
        long id,
        long accountId,
        String level,
        String message,
        Instant createdAt
) {
}
