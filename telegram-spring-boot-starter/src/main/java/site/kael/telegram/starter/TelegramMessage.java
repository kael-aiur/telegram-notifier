package site.kael.telegram.starter;

import java.time.LocalDateTime;

/**
 * 一条 Telegram 消息的完整元数据,作为 {@code TelegramClient.peekUnreadMessage} 的返回元素。
 *
 * <p>{@code receivedAt} 为系统时区的 {@link LocalDateTime},便于上层直接用于通知模板渲染。
 */
public record TelegramMessage(
        long accountId,
        long chatId,
        long messageId,
        String chatTitle,
        String chatType,
        long senderId,
        String senderName,
        String senderUsername,
        LocalDateTime receivedAt,
        String text
) {
}
