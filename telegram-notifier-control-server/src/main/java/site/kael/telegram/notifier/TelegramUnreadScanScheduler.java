package site.kael.telegram.notifier;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import site.kael.telegram.starter.AuthorizationState;
import site.kael.telegram.starter.TelegramAccountSessionManager;
import site.kael.telegram.starter.TelegramScanRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Service
class TelegramUnreadScanScheduler {
    private static final Logger LOGGER = Logger.getLogger(TelegramUnreadScanScheduler.class.getName());

    private final TelegramAccountService accounts;
    private final TelegramAccountSessionManager sessions;
    private final UnreadScanProperties properties;
    private final Map<Long, Instant> lastScans = new ConcurrentHashMap<>();

    TelegramUnreadScanScheduler(TelegramAccountService accounts,
                                TelegramAccountSessionManager sessions,
                                UnreadScanProperties properties) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${telegram-notifier.unread-scan.scheduler-delay-ms:10000}")
    void scanDueAccounts() {
        var now = Instant.now();
        for (TelegramAccount account : accounts.list()) {
            if (!account.enabled() || account.authorizationState() != AuthorizationState.READY) {
                continue;
            }
            var chatIds = properties.chatIds(account.id());
            if (chatIds.isEmpty()) {
                LOGGER.fine(() -> "telegram account " + account.id() + " has no monitored chatIds; skip unread scan");
                continue;
            }
            var lastScan = lastScans.get(account.id());
            if (lastScan != null && Duration.between(lastScan, now).getSeconds() < account.scanFrequencySeconds()) {
                continue;
            }
            lastScans.put(account.id(), now);
            sessions.scan(new TelegramScanRequest(account.id(), chatIds, properties.getMaxMessagesPerChat()));
        }
    }
}
