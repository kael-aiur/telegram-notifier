package site.kael.telegram.notifier.core.model;

import java.time.Instant;

public record AccountMonitoringLog(
        long id,
        long accountId,
        long chatId,
        Instant scannedAt,
        int unreadCount,
        int notifiedCount,
        Instant createdAt
) {
}
