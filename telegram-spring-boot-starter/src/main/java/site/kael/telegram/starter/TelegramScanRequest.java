package site.kael.telegram.starter;

import java.util.List;

public record TelegramScanRequest(
        long accountId,
        List<Long> chatIds,
        int maxMessagesPerChat
) {
    public TelegramScanRequest {
        chatIds = chatIds == null ? List.of() : List.copyOf(chatIds);
        maxMessagesPerChat = maxMessagesPerChat <= 0 ? 20 : maxMessagesPerChat;
    }

    public TelegramScanRequest(long accountId, List<Long> chatIds) {
        this(accountId, chatIds, 20);
    }
}
