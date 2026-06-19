package site.kael.telegram.starter;

import java.time.Instant;

public record TelegramMessageEvent(
        long accountId,
        long chatId,
        long messageId,
        String chatTitle,
        String chatType,
        long senderId,
        String senderName,
        String senderUsername,
        Instant receivedAt,
        String text
) {
}
