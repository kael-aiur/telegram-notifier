package site.kael.telegram.notifier;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "telegram-notifier.unread-scan")
class UnreadScanProperties {
    private Map<Long, List<Long>> accounts = new LinkedHashMap<>();
    private int maxMessagesPerChat = 20;

    public Map<Long, List<Long>> getAccounts() {
        return accounts;
    }

    public void setAccounts(Map<Long, List<Long>> accounts) {
        this.accounts = accounts == null ? new LinkedHashMap<>() : new LinkedHashMap<>(accounts);
    }

    public int getMaxMessagesPerChat() {
        return maxMessagesPerChat;
    }

    public void setMaxMessagesPerChat(int maxMessagesPerChat) {
        this.maxMessagesPerChat = maxMessagesPerChat <= 0 ? 20 : maxMessagesPerChat;
    }

    List<Long> chatIds(long accountId) {
        return accounts.getOrDefault(accountId, List.of()).stream()
                .filter(id -> id != null && id != 0L)
                .distinct()
                .toList();
    }
}
